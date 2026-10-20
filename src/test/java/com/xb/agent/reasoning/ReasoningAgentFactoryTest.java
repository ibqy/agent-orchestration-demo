package com.xb.agent.reasoning;

import com.xb.agent.FakeChatModel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 推理 Agent 工厂测试 —— 验证三种模式真的被组装上线
 *
 * <p>心得：这个测试守的是"写好的代码真的有人用"。
 * 升级前 PlanAndSolveAgent / SelfReflectionAgent / ReasoningMode 三个类
 * 完整实现却零引用（WorkerAgent 硬编码 new ReActAgent）——
 * 编译通过、测试全绿，能力却是死的。工厂测试按 prompt 特征断言行为差异，
 * 模式接没接上线，一测便知。</p>
 *
 * @author ibqy
 */
@DisplayName("ReasoningAgentFactory 推理模式工厂")
class ReasoningAgentFactoryTest {

    private final ReasoningProperties props = new ReasoningProperties();

    /** 路由：按 prompt 特征给出各角色的回复，模拟完整多 Agent 对话 */
    private final FakeChatModel model = new FakeChatModel(prompt -> {
        if (prompt.contains("把以下任务拆解为有序步骤")) {
            return "1. 查询订单\n2. 汇总结果";           // Plan-and-Solve 的初始计划
        }
        if (prompt.contains("检查结果")) {
            return "PASS";                                // Self-Reflection 的评审
        }
        if (prompt.contains("ReAct Agent")) {
            return "THOUGHT: 直接回答\nANSWER: 完成";      // ReAct 循环
        }
        return "步骤结果";
    });

    private final ReasoningAgentFactory factory = new ReasoningAgentFactory(model, props);

    @Test
    @DisplayName("REACT 模式走 ReAct 循环（系统提示含 ReAct 协议）")
    void reactModeUsesReactLoop() {
        ReasoningAgent agent = factory.create(ReasoningMode.REACT, ToolInvoker.noop(), StepListener.noop());
        String result = agent.execute("查一下订单");

        assertThat(result).isEqualTo("完成");
        assertThat(model.prompts().get(0)).contains("ReAct Agent");
    }

    @Test
    @DisplayName("PLAN_AND_SOLVE 模式先规划后执行（首轮 prompt 是规划提示）")
    void planAndSolveModePlansFirst() {
        ReasoningAgent agent = factory.create(ReasoningMode.PLAN_AND_SOLVE, ToolInvoker.noop(), StepListener.noop());
        agent.execute("走一遍售后流程");

        assertThat(model.prompts().get(0)).contains("把以下任务拆解为有序步骤");
        // 规划 → 逐步执行 → 每步校验 → 汇总，至少 4 次调用
        assertThat(model.callCount()).isGreaterThanOrEqualTo(4);
    }

    @Test
    @DisplayName("SELF_REFLECTION 模式在外层包自检（评审 prompt 出现）")
    void selfReflectionModeReflects() {
        ReasoningAgent agent = factory.create(ReasoningMode.SELF_REFLECTION, ToolInvoker.noop(), StepListener.noop());
        String result = agent.execute("算笔账");

        assertThat(result).isEqualTo("完成");
        assertThat(model.prompts()).anyMatch(p -> p.contains("检查结果"));
    }

    @Test
    @DisplayName("mode 传 null 时用配置默认模式")
    void nullModeFallsBackToConfiguredDefault() {
        props.setMode(ReasoningMode.PLAN_AND_SOLVE);
        ReasoningAgent agent = factory.create(null, ToolInvoker.noop(), StepListener.noop());
        agent.execute("测试默认模式");

        assertThat(model.prompts().get(0)).contains("把以下任务拆解为有序步骤");
        assertThat(factory.defaultMode()).isEqualTo(ReasoningMode.PLAN_AND_SOLVE);
    }

    @Test
    @DisplayName("步骤监听器收到推理事件（SSE 的数据源）")
    void listenerReceivesStepEvents() {
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        StepListener listener = (type, message) -> events.add(type + ":" + message);

        ReasoningAgent agent = factory.create(ReasoningMode.REACT, ToolInvoker.noop(), listener);
        agent.execute("查一下订单");

        assertThat(events).anyMatch(e -> e.startsWith("TASK:"));
        assertThat(events).anyMatch(e -> e.startsWith("THOUGHT:"));
        assertThat(events).anyMatch(e -> e.startsWith("ANSWER:"));
    }
}
