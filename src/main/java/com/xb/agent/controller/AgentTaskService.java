package com.xb.agent.controller;

import com.xb.agent.guard.ConcurrencyLimiter;
import com.xb.agent.guard.GuardProperties;
import com.xb.agent.guard.HITLRejectedException;
import com.xb.agent.guard.HitlProperties;
import com.xb.agent.guard.LoopBreaker;
import com.xb.agent.guard.TokenBudget;
import com.xb.agent.memory.LayeredMemoryService;
import com.xb.agent.multi.MultiAgentOrchestrator;
import com.xb.agent.observe.AgentMetrics;
import com.xb.agent.observe.AgentTraceLogger;
import com.xb.agent.reasoning.ReasoningMode;
import com.xb.agent.reasoning.StepListener;
import com.xb.agent.reasoning.ToolInvoker;
import com.xb.agent.state.AgentEvent;
import com.xb.agent.state.AgentState;
import com.xb.agent.state.ApprovalGate;
import com.xb.agent.state.SnapshotStore;
import com.xb.agent.state.StepEventBus;
import com.xb.agent.state.TaskRunner;
import com.xb.agent.state.TaskSnapshot;
import com.xb.agent.tool.AgentTool;
import com.xb.agent.tool.ToolDescriptor;
import com.xb.agent.tool.ToolExecutor;
import com.xb.agent.tool.ToolRegistry;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 全链路编排服务 —— 串联所有模块
 *
 * 心得：这条链路串起来的顺序就是生产 Agent 的真实形态:
 * 并发限制 → 记忆上下文 → FSM 启动 → 多Agent执行(工具走防护管道) → 状态收尾
 *
 * 工具调用管道（guardedInvoker）是关键设计:
 * LoopBreaker → HITL审批 → Trace Span → 权限/熔断执行 → Token记账
 * 每一环独立，谁出问题都不影响其他环
 *
 * <p><b>异步执行模型</b>（本期重构）：submitTask 立即返回 taskId，
 * 全链路跑在虚拟线程上。这不是"为了炫技"——HITL 的完整语义要求如此：
 * 高危工具触发审批时，执行线程必须能 park 住等人工决定，
 * 而 HTTP 请求线程不能陪着一起挂几分钟。客户端改为轮询
 * {@code GET /agent/tasks/{id}} 或订阅 {@code GET /agent/tasks/{id}/stream}。</p>
 *
 * @author ibqy
 */
@Service
public class AgentTaskService {

    private static final Logger log = LoggerFactory.getLogger(AgentTaskService.class);

    private final MultiAgentOrchestrator orchestrator;
    private final LayeredMemoryService memoryService;
    private final TaskRunner taskRunner;
    private final SnapshotStore snapshotStore;
    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;
    private final LoopBreaker loopBreaker;
    private final TokenBudget tokenBudget;
    private final ConcurrencyLimiter concurrencyLimiter;
    private final GuardProperties guardProps;
    private final HitlProperties hitlProps;
    private final ApprovalGate approvalGate;
    private final StepEventBus stepBus;
    private final AgentMetrics metrics;
    private final AgentTraceLogger trace;

    public AgentTaskService(MultiAgentOrchestrator orchestrator,
                            LayeredMemoryService memoryService,
                            TaskRunner taskRunner,
                            SnapshotStore snapshotStore,
                            ToolRegistry toolRegistry,
                            ToolExecutor toolExecutor,
                            LoopBreaker loopBreaker,
                            TokenBudget tokenBudget,
                            ConcurrencyLimiter concurrencyLimiter,
                            GuardProperties guardProps,
                            HitlProperties hitlProps,
                            ApprovalGate approvalGate,
                            StepEventBus stepBus,
                            AgentMetrics metrics,
                            AgentTraceLogger trace) {
        this.orchestrator = orchestrator;
        this.memoryService = memoryService;
        this.taskRunner = taskRunner;
        this.snapshotStore = snapshotStore;
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
        this.loopBreaker = loopBreaker;
        this.tokenBudget = tokenBudget;
        this.concurrencyLimiter = concurrencyLimiter;
        this.guardProps = guardProps;
        this.hitlProps = hitlProps;
        this.approvalGate = approvalGate;
        this.stepBus = stepBus;
        this.metrics = metrics;
        this.trace = trace;
    }

    /**
     * 提交任务: 异步执行，立即返回 taskId
     *
     * @param userId 用户ID
     * @param goal   用户目标描述
     * @param mode   推理模式（null = 用 agent.reasoning.mode 配置默认值）
     * @return taskId（执行结果通过 GET /agent/tasks/{id} 或 SSE 流获取）
     */
    public String submitTask(String userId, String goal, ReasoningMode mode) {
        // 1. 并发限制（同步快速失败，不需要异步）
        if (!concurrencyLimiter.tryAcquire(userId, guardProps.getMaxConcurrentPerUser())) {
            return "并发任务数已达上限 " + guardProps.getMaxConcurrentPerUser() + "，请稍后再试";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        metrics.taskStarted();
        stepBus.register(taskId);

        // 2. 虚拟线程跑全链路——HITL 审批时线程 park，HTTP 请求早已返回
        Thread.ofVirtual().name("agent-task-" + taskId).start(() -> {
            try {
                runTask(taskId, userId, goal, mode);
            } finally {
                concurrencyLimiter.release(userId);
            }
        });
        return taskId;
    }

    /**
     * 全链路执行（并发限制 → 记忆上下文 → FSM → 多Agent执行 → 状态收尾）
     */
    private void runTask(String taskId, String userId, String goal, ReasoningMode mode) {
        log.info("[AgentTask] 任务启动 task={} mode={}", taskId, mode);
        stepBus.publish(taskId, "TASK", "任务启动: " + goal);

        try {
            // 2. 创建快照 + FSM 启动
            TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "default", goal);
            snapshot.setReasoningMode(mode);
            snapshotStore.save(snapshot);
            taskRunner.fireEvent(taskId, AgentEvent.START);

            // 3. 分层记忆构建上下文（实体 → 长时 → 短时）
            String memoryCtx = memoryService.buildContext(taskId, userId, goal);
            taskRunner.fireEvent(taskId, AgentEvent.PLAN_DONE);

            // 4. 多 Agent 执行（工具清单 + 记忆上下文拼进任务，LLM 才知道能调什么）
            String fullGoal = toolRegistry.buildToolListPrompt()
                    + (memoryCtx.isEmpty() ? "" : "\n用户背景:\n" + memoryCtx)
                    + "\n任务: " + goal;
            StepListener listener = (type, message) -> stepBus.publish(taskId, type, message);
            String result = orchestrator.run(fullGoal, guardedInvoker(taskId, userId), mode, listener);

            // 5. 状态收尾
            taskRunner.fireEvent(taskId, AgentEvent.TASK_DONE);
            taskRunner.fireEvent(taskId, AgentEvent.REVIEW_PASS);
            metrics.taskCompleted();
            memoryService.onTaskFinished(taskId, userId, goal, result);

            // 6. 结果落快照（客户端断线也能通过查询接口拿到）+ 终态事件
            TaskSnapshot done = snapshotStore.load(taskId);
            if (done != null) {
                done.setResult(result);
                snapshotStore.save(done);
            }
            stepBus.publish(taskId, "RESULT", result);
            log.info("[AgentTask] 任务完成 task={}", taskId);
        } catch (HITLRejectedException e) {
            // 人工拒绝/超时：状态还停在 WAIT_HUMAN，统一收敛到 FAILED
            stepBus.publish(taskId, "HITL", e.getMessage());
            TaskSnapshot snapshot = snapshotStore.load(taskId);
            if (snapshot != null && snapshot.getState() == AgentState.WAIT_HUMAN) {
                taskRunner.fireEvent(taskId, AgentEvent.HUMAN_ABORT);
            }
            metrics.taskFailed();
            log.warn("[AgentTask] 任务因人工审批未通过而终止 task={}: {}", taskId, e.getMessage());
        } catch (Exception e) {
            taskRunner.fireEvent(taskId, AgentEvent.TASK_FAILED);
            metrics.taskFailed();
            stepBus.publish(taskId, "ERROR", e.getMessage());
            log.error("[AgentTask] 任务失败 task={}", taskId, e);
        } finally {
            stepBus.complete(taskId);
        }
    }

    /** 工具调用防护管道: 熔断 → HITL审批 → Trace → 执行 → Token记账 */
    private ToolInvoker guardedInvoker(String taskId, String userId) {
        return (toolName, argsJson) -> {
            // 循环熔断
            String blocked = loopBreaker.check(taskId, toolName, argsJson, guardProps.getMaxToolCalls());
            if (blocked != null) return blocked;

            AgentTool tool = toolRegistry.get(toolName);
            if (tool == null) {
                metrics.hallucinatedTool();  // Agent 幻觉式调用了不存在的工具
            } else if (hitlProps.isHighRisk(toolName)
                    || tool.descriptor().getRisk() == ToolDescriptor.RiskLevel.HIGH) {
                // 高危工具 → HITL 暂停。时序关键：先登记审批单，再流转状态，最后 park——
                // 反过来的话，审批请求可能在状态流转前到达，找不到登记记录白等一轮
                stepBus.publish(taskId, "HITL", "高危工具 " + toolName + " 等待人工审批");
                var decision = approvalGate.request(taskId, toolName, argsJson);
                taskRunner.fireEvent(taskId, AgentEvent.NEED_HUMAN);
                metrics.hitlTriggered();
                log.warn("[AgentTask] 高危工具 {} 触发人工介入 task={}", toolName, taskId);

                boolean approved = approvalGate.await(taskId, decision,
                        Duration.ofMinutes(hitlProps.getApprovalTimeoutMinutes()));
                if (!approved) {
                    // 拒绝/超时：抛异常穿透调用栈，任务收敛到 FAILED（详见 HITLRejectedException 注释）
                    throw new HITLRejectedException(toolName, "已拒绝或审批超时");
                }
                stepBus.publish(taskId, "HITL", "工具 " + toolName + " 已获批准，继续执行");
                taskRunner.resume(taskId);  // WAIT_HUMAN → RUNNING
            }

            // 链路追踪 + 执行
            Span span = trace.startToolSpan(taskId, toolName);
            metrics.toolCalled();
            try {
                String observation = toolExecutor.execute(userId, toolName, argsJson);
                trace.endSpan(span, observation);
                recordStep(taskId, toolName, argsJson, observation);

                // Token 记账
                tokenBudget.add(taskId, tokenBudget.estimate(observation));
                String overBudget = tokenBudget.checkBudget(taskId, guardProps.getTokenBudget());
                if (overBudget != null) {
                    taskRunner.fireEvent(taskId, AgentEvent.TASK_FAILED);
                    return overBudget;
                }
                return observation;
            } catch (Exception e) {
                metrics.toolFailed(toolName);
                trace.endSpanError(span, e);
                recordStep(taskId, toolName, argsJson, "[ERROR] " + e.getMessage());
                return "[ERROR] " + e.getMessage();
            }
        };
    }

    /**
     * 执行轨迹落快照 —— TaskSnapshot.addStep 早期定义了却没有任何调用方，
     * 快照的 steps 永远是空列表，"断点续跑"缺了最关键的一环数据。
     * 每次工具调用（含失败）都记一条，复盘时能看到完整决策链。
     */
    private void recordStep(String taskId, String toolName, String argsJson, String observation) {
        TaskSnapshot snapshot = snapshotStore.load(taskId);
        if (snapshot == null) return;
        snapshot.addStep(new TaskSnapshot.StepRecord(
                taskId, null, toolName, argsJson, observation, System.currentTimeMillis()));
        snapshotStore.save(snapshot);
    }

    /**
     * 人工确认: 唤醒等待审批的任务线程（WAIT_HUMAN → RUNNING，被 park 的执行继续）
     *
     * @return true=成功释放了一个等待中的审批
     */
    public boolean confirm(String taskId) {
        boolean released = approvalGate.confirm(taskId);
        if (released) {
            taskRunner.resume(taskId);  // 状态流转由被唤醒的执行线程看到
        }
        return released;
    }

    /**
     * 人工终止: 以"拒绝"唤醒等待中的任务线程，任务收敛到 FAILED
     *
     * @return true=成功释放了一个等待中的审批
     */
    public boolean abort(String taskId) {
        return approvalGate.abort(taskId);
    }

    /**
     * 当前待审批清单（GET /agent/approvals）
     */
    public List<ApprovalGate.PendingApproval> pendingApprovals() {
        return approvalGate.pendingApprovals();
    }

    /**
     * 断点续跑: 读取快照返回任务当前状态（含最终结果，供前端轮询）
     * @param taskId 任务ID
     * @return 任务快照，不存在返回 null
     */
    public TaskSnapshot query(String taskId) {
        return snapshotStore.load(taskId);
    }
}
