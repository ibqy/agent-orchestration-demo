package com.xb.agent.guard;

/**
 * HITL 审批未通过异常 —— 人工拒绝或审批超时
 *
 * <p>为什么用异常而不是返回错误文本：被拒绝的调用如果只返回
 * "[HITL] 审批被拒" 这类 observation，ReAct 循环会把它当成普通工具结果
 * 继续推理，甚至换个参数重试同一个高危工具——人工关口就被绕过了。
 * 抛异常穿透整个调用栈，任务直接收敛到 FAILED，语义才是"终止"。</p>
 *
 * @author ibqy
 */
public class HITLRejectedException extends RuntimeException {

    private final String toolName;

    public HITLRejectedException(String toolName, String reason) {
        super("工具 " + toolName + " 的人工审批未通过: " + reason);
        this.toolName = toolName;
    }

    public String getToolName() {
        return toolName;
    }
}
