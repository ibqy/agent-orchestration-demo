package com.xb.agent.reasoning;

/**
 * 一次推理步骤的结构化结果
 *
 * LLM 输出解析成: thought / 工具调用 / 最终回答 三选一
 */
public class AgentAction {
    private final String thought;      // 本步思考
    private final String toolName;     // 调用工具名，null 表示直接回答
    private final String toolArgs;     // 工具入参 JSON
    private final String finalAnswer;  // 最终回答，null 表示还要继续
    private final boolean needRetry;    // self-reflection 判定不合格

    private AgentAction(String thought, String toolName, String toolArgs,
                        String finalAnswer, boolean needRetry) {
        this.thought = thought;
        this.toolName = toolName;
        this.toolArgs = toolArgs;
        this.finalAnswer = finalAnswer;
        this.needRetry = needRetry;
    }

    public static AgentAction ofTool(String thought, String toolName, String toolArgs) {
        return new AgentAction(thought, toolName, toolArgs, null, false);
    }

    public static AgentAction ofAnswer(String thought, String answer) {
        return new AgentAction(thought, null, null, answer, false);
    }

    public static AgentAction ofRetry(String thought) {
        return new AgentAction(thought, null, null, null, true);
    }

    public boolean isToolCall() { return toolName != null; }
    public boolean isFinal() { return finalAnswer != null; }

    public String getThought() { return thought; }
    public String getToolName() { return toolName; }
    public String getToolArgs() { return toolArgs; }
    public String getFinalAnswer() { return finalAnswer; }
    public boolean isNeedRetry() { return needRetry; }
}
