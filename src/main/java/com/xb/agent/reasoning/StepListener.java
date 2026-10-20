package com.xb.agent.reasoning;

/**
 * 推理步骤监听器 —— 把 ReAct/Plan-and-Solve 循环的每一步事件抛出去
 *
 * <p>心得：Agent 的"黑盒"问题不是日志能解决的——
 * 日志是给开发者事后排查的，步骤事件是给用户实时看到"Agent 正在想什么"的。
 * 推理循环只依赖这个函数式接口，不依赖任何传输实现（SSE/WebSocket/日志），
 * 想换推送方式不用动推理代码。</p>
 *
 * @author ibqy
 */
@FunctionalInterface
public interface StepListener {

    /**
     * 推理循环每产生一个可观察事件时调用
     *
     * @param type    事件类型：THOUGHT / ACTION / OBSERVATION / PLAN / ANSWER
     * @param message 事件内容（已截断，避免把整段上下文推给前端）
     */
    void onStep(String type, String message);

    /** 空实现（默认不需要推送时使用，避免到处判空） */
    static StepListener noop() {
        return (type, message) -> {
        };
    }

    /** 把多个监听器串起来（如同时推 SSE 和写日志） */
    static StepListener composite(StepListener... listeners) {
        return (type, message) -> {
            for (StepListener listener : listeners) {
                if (listener != null) {
                    listener.onStep(type, message);
                }
            }
        };
    }

    /** 内容截断工具：事件推送不追求完整，追求及时 */
    static String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
