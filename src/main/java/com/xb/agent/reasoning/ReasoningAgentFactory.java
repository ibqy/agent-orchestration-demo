package com.xb.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 推理 Agent 工厂 —— 按模式组装推理引擎（把三种模式接上线）
 *
 * <p>心得：Plan-and-Solve / Self-Reflection 写好却没人用，是教学项目最常见的浪费——
 * 工厂的职责就是让"写好的能力"有一个被选中的入口。Self-Reflection 用装饰器模式
 * 包在别的模式外面（可包 ReAct 也可包 Plan-and-Solve），所以组合 rather than 继承。</p>
 *
 * <p>注意：每次调用都新建 Agent 实例——Agent 是无状态的（上下文在 execute() 局部变量里），
 * 单例复用反而要处理并发清理。建造成本可以忽略。</p>
 *
 * @author ibqy
 */
@Component
public class ReasoningAgentFactory {

    private static final Logger log = LoggerFactory.getLogger(ReasoningAgentFactory.class);

    private final ChatModel model;
    private final ReasoningProperties props;

    public ReasoningAgentFactory(ChatModel model, ReasoningProperties props) {
        this.model = model;
        this.props = props;
    }

    /**
     * 按模式创建推理 Agent
     *
     * @param mode        推理模式（null 时用配置默认值）
     * @param toolInvoker 工具调用回调（含防护管道）
     * @param listener    步骤监听器（推 SSE / 日志，可为 {@link StepListener#noop()}）
     * @return 组装好的推理 Agent
     */
    public ReasoningAgent create(ReasoningMode mode, ToolInvoker toolInvoker, StepListener listener) {
        ReasoningMode effective = mode != null ? mode : props.getMode();
        StepListener effectiveListener = listener != null ? listener : StepListener.noop();

        ReasoningAgent agent = switch (effective) {
            case PLAN_AND_SOLVE -> new PlanAndSolveAgent(model, toolInvoker, effectiveListener);
            case SELF_REFLECTION -> new SelfReflectionAgent(
                    new ReActAgent(model, toolInvoker, props.getMaxSteps(), effectiveListener),
                    model, props.getMaxReflectionRetries());
            case REACT -> new ReActAgent(model, toolInvoker, props.getMaxSteps(), effectiveListener);
        };
        log.info("[Factory] 创建推理 Agent: mode={}, maxSteps={}", effective, props.getMaxSteps());
        return agent;
    }

    /** 当前默认模式（供元信息查询） */
    public ReasoningMode defaultMode() {
        return props.getMode();
    }
}
