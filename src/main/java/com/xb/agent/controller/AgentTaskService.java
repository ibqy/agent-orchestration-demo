package com.xb.agent.controller;

import com.xb.agent.guard.ConcurrencyLimiter;
import com.xb.agent.guard.GuardProperties;
import com.xb.agent.guard.LoopBreaker;
import com.xb.agent.guard.TokenBudget;
import com.xb.agent.memory.LayeredMemoryService;
import com.xb.agent.multi.MultiAgentOrchestrator;
import com.xb.agent.observe.AgentMetrics;
import com.xb.agent.observe.AgentTraceLogger;
import com.xb.agent.reasoning.ToolInvoker;
import com.xb.agent.state.AgentEvent;
import com.xb.agent.state.AgentState;
import com.xb.agent.state.SnapshotStore;
import com.xb.agent.state.TaskRunner;
import com.xb.agent.state.TaskSnapshot;
import com.xb.agent.tool.AgentTool;
import com.xb.agent.tool.ToolDescriptor;
import com.xb.agent.tool.ToolRegistry;
import com.xb.agent.tool.ToolExecutor;
import io.opentelemetry.api.trace.Span;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 全链路编排服务 —— 串联所有模块
 *
 * 心得：这条链路串起来的顺序就是生产 Agent 的真实形态:
 * 并发限制 → 记忆上下文 → FSM 启动 → 多Agent执行(工具走防护管道) → 状态收尾
 *
 * 工具调用管道（guardedInvoker）是关键设计:
 * LoopBreaker → HITL检查 → Trace Span → 权限/熔断执行 → Token记账
 * 每一环独立，谁出问题都不影响其他环
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
        this.metrics = metrics;
        this.trace = trace;
    }

    /** 提交任务: 全链路入口 */
    public String submitTask(String userId, String goal) {
        // 1. 并发限制
        if (!concurrencyLimiter.tryAcquire(userId, guardProps.getMaxConcurrentPerUser())) {
            return "并发任务数已达上限 " + guardProps.getMaxConcurrentPerUser() + "，请稍后再试";
        }

        String taskId = UUID.randomUUID().toString().substring(0, 8);
        metrics.taskStarted();

        try {
            // 2. 创建快照 + FSM 启动
            TaskSnapshot snapshot = new TaskSnapshot(taskId, userId, "default", goal);
            snapshotStore.save(snapshot);
            taskRunner.fireEvent(taskId, AgentEvent.START);

            // 3. 分层记忆构建上下文（实体 → 长时 → 短时）
            String memoryCtx = memoryService.buildContext(taskId, userId, goal);
            taskRunner.fireEvent(taskId, AgentEvent.PLAN_DONE);

            // 4. 多 Agent 执行（工具清单 + 记忆上下文拼进任务，LLM 才知道能调什么）
            String fullGoal = toolRegistry.buildToolListPrompt()
                    + (memoryCtx.isEmpty() ? "" : "\n用户背景:\n" + memoryCtx)
                    + "\n任务: " + goal;
            String result = orchestrator.run(fullGoal, guardedInvoker(taskId, userId));

            // 5. 状态收尾
            taskRunner.fireEvent(taskId, AgentEvent.TASK_DONE);
            taskRunner.fireEvent(taskId, AgentEvent.REVIEW_PASS);
            metrics.taskCompleted();
            memoryService.onTaskFinished(taskId, userId, goal, result);

            return "任务 " + taskId + " 完成:\n" + result;
        } catch (Exception e) {
            taskRunner.fireEvent(taskId, AgentEvent.TASK_FAILED);
            metrics.taskFailed();
            log.error("[AgentTask] 任务失败 task={}", taskId, e);
            return "任务 " + taskId + " 失败: " + e.getMessage();
        } finally {
            concurrencyLimiter.release(userId);
        }
    }

    /** 工具调用防护管道: 熔断 → HITL → Trace → 执行 → Token记账 */
    private ToolInvoker guardedInvoker(String taskId, String userId) {
        return (toolName, argsJson) -> {
            // 循环熔断
            String blocked = loopBreaker.check(taskId, toolName, argsJson, guardProps.getMaxToolCalls());
            if (blocked != null) return blocked;

            AgentTool tool = toolRegistry.get(toolName);
            if (tool == null) {
                metrics.hallucinatedTool();  // Agent 幻觉式调用了不存在的工具
            } else if (tool.descriptor().getRisk() == ToolDescriptor.RiskLevel.HIGH) {
                // 高危工具 → HITL 暂停
                taskRunner.fireEvent(taskId, AgentEvent.NEED_HUMAN);
                metrics.hitlTriggered();
                log.warn("[AgentTask] 高危工具 {} 触发人工介入 task={}", toolName, taskId);
                return "[HITL] 高危工具 " + toolName + " 需人工确认。请等待审批后通过 /confirm 接口恢复任务";
            }

            // 链路追踪 + 执行
            Span span = trace.startToolSpan(taskId, toolName);
            metrics.toolCalled();
            try {
                String observation = toolExecutor.execute(userId, toolName, argsJson);
                trace.endSpan(span, observation);

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
                return "[ERROR] " + e.getMessage();
            }
        };
    }

    /**
     * 人工确认: WAIT_HUMAN → RUNNING，并通过 resume 恢复执行
     *
     * TODO: 真正恢复执行需要在此处将任务重新提交给 orchestrator（如通过
     *       ApplicationEventPublisher 发布 ResumeEvent，由监听器异步重新编排）。
     *       当前架构中 submitTask() 是同步的，无法从中间断点重新注入执行流。
     */
    public boolean confirm(String taskId) {
        AgentState resumed = taskRunner.resume(taskId);
        return resumed == AgentState.RUNNING;
    }

    /** 人工终止: WAIT_HUMAN → FAILED */
    public boolean abort(String taskId) {
        return taskRunner.fireEvent(taskId, AgentEvent.HUMAN_ABORT);
    }

    /** 断点续跑: 读快照返回当前状态 */
    public TaskSnapshot query(String taskId) {
        return snapshotStore.load(taskId);
    }
}
