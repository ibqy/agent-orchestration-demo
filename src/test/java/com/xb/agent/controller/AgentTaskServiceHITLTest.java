package com.xb.agent.controller;

import com.xb.agent.FakeChatModel;
import com.xb.agent.RedisTestFixtures;
import com.xb.agent.guard.ConcurrencyLimiter;
import com.xb.agent.guard.GuardProperties;
import com.xb.agent.guard.HitlProperties;
import com.xb.agent.guard.LoopBreaker;
import com.xb.agent.guard.TokenBudget;
import com.xb.agent.memory.EntityMemory;
import com.xb.agent.memory.LayeredMemoryService;
import com.xb.agent.memory.LongTermMemory;
import com.xb.agent.memory.MemoryCompressor;
import com.xb.agent.memory.ShortTermMemory;
import com.xb.agent.multi.MultiAgentOrchestrator;
import com.xb.agent.multi.PlannerAgent;
import com.xb.agent.multi.ReviewerAgent;
import com.xb.agent.multi.WorkerAgent;
import com.xb.agent.observe.AgentMetrics;
import com.xb.agent.observe.AgentTraceLogger;
import com.xb.agent.reasoning.ReasoningAgentFactory;
import com.xb.agent.reasoning.ReasoningMode;
import com.xb.agent.reasoning.ReasoningProperties;
import com.xb.agent.state.AgentState;
import com.xb.agent.state.ApprovalGate;
import com.xb.agent.state.SnapshotStore;
import com.xb.agent.state.StepEventBus;
import com.xb.agent.state.TaskRunner;
import com.xb.agent.tool.AgentTool;
import com.xb.agent.tool.ToolDescriptor;
import com.xb.agent.tool.ToolExecutor;
import com.xb.agent.tool.ToolPermission;
import com.xb.agent.tool.ToolRegistry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HITL 全流程测试 —— 提交 → 高危工具触发审批 → 任务 park → 批准 → 恢复 → 完成
 *
 * <p>这是整个改造里最重要的测试：它验证的不是某个类的方法，
 * 而是"人工介入"这条链路真的通了。升级前 confirm() 只翻状态不恢复执行
 * （代码里留着 TODO），任务实际永远停在 WAIT_HUMAN——
 * 单元测试全绿也发现不了，因为没人测过完整链路。</p>
 *
 * <p>Redis 用内存桩（{@link RedisTestFixtures}）跑真实 Guard/快照逻辑；
 * ChatModel 用脚本化假模型驱动 Planner/Worker/Reviewer 全流程。</p>
 *
 * @author ibqy
 */
@DisplayName("AgentTaskService HITL 全流程")
class AgentTaskServiceHITLTest {

    private StringRedisTemplate redis;
    private ToolRegistry toolRegistry;
    private AgentMetrics metrics;
    private StepEventBus stepBus;
    private ApprovalGate approvalGate;
    private AgentTaskService taskService;
    private HitlProperties hitlProps;

    /**
     * 脚本化假模型：规划 → ReAct/Plan-and-Solve 调高危工具 → 评审通过
     *
     * <p>两个教学要点：</p>
     * <ol>
     *   <li>每种 prompt 都要有分支——漏一个分支（如 P&S 的计划 prompt）不会报错，
     *       只会静默走到默认回复，表现为"计划 0 步"这类难查的现象。</li>
     *   <li>推理类响应要有状态（计数器）：无状态路由器会让 ReAct 每轮都返回同一个
     *       ACTION，HITL 批准后继续调同一个工具，二次触发审批——真实模型不会这样，
     *       但测试里必须防住。</li>
     * </ol>
     */
    private final java.util.concurrent.atomic.AtomicInteger reasoningCalls = new java.util.concurrent.atomic.AtomicInteger();

    private final FakeChatModel model = new FakeChatModel(prompt -> {
        if (prompt.contains("任务规划师")) {                       // 多 Agent Planner
            return "1 | 删除问题订单 | -";
        }
        if (prompt.contains("把以下任务拆解为有序步骤")) {           // Plan-and-Solve 规划
            return "1. 删除问题订单\n2. 汇总处理结果";
        }
        if (prompt.contains("当前计划是否需要修正")) {               // Plan-and-Solve 每步校验
            return "NO";
        }
        if (prompt.contains("ReAct Agent") || prompt.contains("执行以下子步骤")) {
            // 第一次走工具调用（触发 HITL），拿到观察结果后收尾
            return reasoningCalls.getAndIncrement() == 0
                    ? "THOUGHT: 用户要求删除订单，调用删除工具\n"
                      + "ACTION: {\"tool\": \"deleteOrder\", \"args\": {\"orderId\": \"A1001\"}}"
                    : "THOUGHT: 已获得工具执行结果\nANSWER: 订单 A1001 已删除";
        }
        if (prompt.contains("检查:")) {                            // Reviewer / Reflection 评审
            return "PASS";
        }
        return "已完成";
    });

    @BeforeEach
    void setUp() {
        redis = RedisTestFixtures.inMemoryRedis();
        toolRegistry = new ToolRegistry();
        metrics = new AgentMetrics();
        stepBus = new StepEventBus();
        approvalGate = new ApprovalGate();

        // 注册一个 HIGH 风险工具（命中 HITL）
        toolRegistry.register(new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("deleteOrder", "删除订单", new String[]{"orderId"},
                        "order:write", ToolDescriptor.RiskLevel.HIGH, 5000, "1.0");
            }

            @Override
            public String execute(String argsJson) {
                return "订单 A1001 已删除";
            }
        });

        // 推理工厂 + 多 Agent 编排器（全部真实组件）
        ReasoningProperties reasoningProps = new ReasoningProperties();
        ReasoningAgentFactory agentFactory = new ReasoningAgentFactory(model, reasoningProps);
        PlannerAgent planner = new PlannerAgent(model);
        WorkerAgent worker = new WorkerAgent(agentFactory);
        ReviewerAgent reviewer = new ReviewerAgent(model);
        MultiAgentOrchestrator orchestrator =
                new MultiAgentOrchestrator(planner, worker, reviewer, new com.xb.agent.multi.BatchRunner());

        // 记忆体系（短时走内存 Redis 桩，长时/实体为内存实现）
        LayeredMemoryService memoryService = new LayeredMemoryService(
                new ShortTermMemory(redis), new LongTermMemory(), new EntityMemory(),
                new MemoryCompressor(model));

        // 防护体系（全部真实组件 + 内存 Redis 桩）
        GuardProperties guardProps = new GuardProperties();
        hitlProps = new HitlProperties();
        hitlProps.setHighRiskTools(Set.of("dropTable", "deleteOrder", "refund"));
        hitlProps.setApprovalTimeoutMinutes(1);

        taskService = new AgentTaskService(
                orchestrator, memoryService,
                new TaskRunner(new SnapshotStore(redis)),
                new SnapshotStore(redis),
                toolRegistry,
                new ToolExecutor(toolRegistry, new ToolPermission()),
                new LoopBreaker(redis), new TokenBudget(redis),
                new ConcurrencyLimiter(redis), guardProps, hitlProps,
                approvalGate, stepBus, metrics,
                new AgentTraceLogger(GlobalOpenTelemetry.get()));
    }

    @Test
    @DisplayName("完整链路：提交 → 审批等待 → 批准 → 恢复执行 → 成功出结果")
    void fullHitlFlowWithApproval() throws InterruptedException {
        // 1. 提交任务（异步，立即返回 taskId）
        String taskId = taskService.submitTask("user1", "删除订单 A1001", ReasoningMode.REACT);
        assertThat(taskId).isNotBlank();

        // 2. 等待进入 WAIT_HUMAN（高危工具触发审批）
        AgentState state = awaitState(taskId, AgentState.WAIT_HUMAN, 10);
        assertThat(state).isEqualTo(AgentState.WAIT_HUMAN);

        // 3. 待审批清单里能看到这笔审批
        var pending = taskService.pendingApprovals();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).toolName()).isEqualTo("deleteOrder");
        assertThat(pending.get(0).argsJson()).contains("A1001");
        assertThat(metrics.getHitlCount()).isEqualTo(1);

        // 4. 人工批准 → 被 park 的执行线程恢复
        assertThat(taskService.confirm(taskId)).isTrue();

        // 5. 等待任务完成，快照里应写入结果（客户端断线也能查到）
        state = awaitState(taskId, AgentState.SUCCESS, 10);
        assertThat(state).isEqualTo(AgentState.SUCCESS);

        var snapshot = taskService.query(taskId);
        assertThat(snapshot).isNotNull();
        assertThat(snapshot.getResult()).contains("执行结果");
        assertThat(snapshot.getSteps()).isNotEmpty();
        assertThat(metrics.getCompletedTasks()).isEqualTo(1);

        // 6. 审批单已清空
        assertThat(taskService.pendingApprovals()).isEmpty();
    }

    @Test
    @DisplayName("拒绝链路：abort 后任务收敛到 FAILED，不再继续执行")
    void abortTerminatesTask() throws InterruptedException {
        String taskId = taskService.submitTask("user1", "删除订单 A1001", ReasoningMode.REACT);

        assertThat(awaitState(taskId, AgentState.WAIT_HUMAN, 10))
                .isEqualTo(AgentState.WAIT_HUMAN);

        // 人工拒绝
        assertThat(taskService.abort(taskId)).isTrue();

        // 任务收敛到 FAILED（不是卡在 WAIT_HUMAN，也不是变成 SUCCESS）
        assertThat(awaitState(taskId, AgentState.FAILED, 10))
                .isEqualTo(AgentState.FAILED);
        assertThat(metrics.getFailedTasks()).isEqualTo(1);
        assertThat(taskService.pendingApprovals()).isEmpty();
    }

    @Test
    @DisplayName("模式入链：提交时指定 PLAN_AND_SOLVE，快照记录该模式")
    void reasoningModeIsRecorded() throws InterruptedException {
        String taskId = taskService.submitTask("user1", "删除订单 A1001", ReasoningMode.PLAN_AND_SOLVE);

        assertThat(awaitState(taskId, AgentState.WAIT_HUMAN, 10))
                .isEqualTo(AgentState.WAIT_HUMAN);

        var snapshot = taskService.query(taskId);
        assertThat(snapshot.getReasoningMode()).isEqualTo(ReasoningMode.PLAN_AND_SOLVE);
        // Plan-and-Solve 的规划 prompt 确实被调用过（模式真的接上了）
        assertThat(model.prompts()).anyMatch(p -> p.contains("把以下任务拆解为有序步骤"));

        taskService.abort(taskId);  // 清理，避免测试间干扰
    }

    // ==================== 辅助 ====================

    /** 轮询等待任务进入目标状态（虚拟线程异步执行，不能假设时序） */
    private AgentState awaitState(String taskId, AgentState expected, int maxSeconds)
            throws InterruptedException {
        for (int i = 0; i < maxSeconds * 20; i++) {
            var snapshot = taskService.query(taskId);
            if (snapshot != null && snapshot.getState() == expected) {
                return expected;
            }
            Thread.sleep(50);
        }
        var snapshot = taskService.query(taskId);
        throw new AssertionError("等待超时：期望 " + expected + "，实际 " +
                (snapshot == null ? "快照不存在" : snapshot.getState()));
    }
}
