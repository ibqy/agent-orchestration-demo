package com.xb.agent.reasoning;

/**
 * 工具调用回调 —— 解耦推理模式和具体工具实现
 *
 * 心得：reasoning 层不该依赖 tool 层细节，
 * 否则每种推理模式都要重复写工具分发逻辑
 */
@FunctionalInterface
public interface ToolInvoker {

    /**
     * @param toolName 工具名
     * @param argsJson 入参 JSON
     * @return 观察结果（返回给 LLM）
     */
    String invoke(String toolName, String argsJson);

    /** 空实现（测试用） */
    static ToolInvoker noop() {
        return (name, args) -> "工具 " + name + " 未注册，可用工具: []";
    }
}
