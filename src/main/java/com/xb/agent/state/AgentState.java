package com.xb.agent.state;

/** Agent 任务状态枚举 —— FSM 节点 */
public enum AgentState {
    INIT("初始化"),
    PLANNING("生成计划中"),
    RUNNING("执行子任务中"),
    REVIEW("结果评审中"),
    WAIT_HUMAN("等待人工确认"),
    SUCCESS("成功"),
    FAILED("失败");

    private final String desc;

    AgentState(String desc) { this.desc = desc; }

    public String getDesc() { return desc; }
}
