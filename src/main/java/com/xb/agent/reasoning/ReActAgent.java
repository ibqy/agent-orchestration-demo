package com.xb.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 基础 ReAct: Thought → Action → Observation 循环
 *
 * 心得：ReAct 的关键不是循环本身，而是 Observation 要回填进上下文，
 * 否则模型不知道上一步发生了什么，会重复调用
 *
 * @author ibqy
 */
public class ReActAgent implements ReasoningAgent {

    private static final Logger log = LoggerFactory.getLogger(ReActAgent.class);

    private static final String SYSTEM_PROMPT = """
            你是一个 ReAct Agent，逐步推理解决问题。
            每轮输出以下格式之一：

            THOUGHT: 你的思考
            ACTION: {"tool": "工具名", "args": {"参数名": "值"}}

            或当你已得到答案：

            THOUGHT: 你的思考
            ANSWER: 最终答案
            """;

    private final ChatModel model;
    private final ToolInvoker toolInvoker;
    private final int maxSteps;

    public ReActAgent(ChatModel model, ToolInvoker toolInvoker, int maxSteps) {
        this.model = model;
        this.toolInvoker = toolInvoker;
        this.maxSteps = maxSteps;
    }

    @Override
    public String execute(String goal) {
        StringBuilder context = new StringBuilder(SYSTEM_PROMPT)
                .append("\n任务: ").append(goal).append("\n\n");

        for (int step = 1; step <= maxSteps; step++) {
            String output = model.chat(context.toString());
            log.info("[ReAct] step={} output={}", step, abbreviate(output));

            AgentAction action = ActionParser.parse(output);
            context.append(output).append("\n");

            if (action.isFinal()) {
                return action.getFinalAnswer();
            }
            if (action.isToolCall()) {
                String observation = toolInvoker.invoke(action.getToolName(), action.getToolArgs());
                context.append("OBSERVATION: ").append(observation).append("\n\n");
            } else {
                // 解析失败，把提示喂回去让模型纠正格式
                context.append("OBSERVATION: 输出格式错误，请严格按 THOUGHT/ACTION 或 THOUGHT/ANSWER 格式\n\n");
            }
        }
        return "已达最大推理步数，任务未完成";
    }

    private String abbreviate(String s) {
        return s.length() > 120 ? s.substring(0, 120) + "..." : s;
    }
}
