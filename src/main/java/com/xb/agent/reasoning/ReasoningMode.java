package com.xb.agent.reasoning;

/** 推理模式枚举 —— 基础 ReAct 之外的扩展范式 */
public enum ReasoningMode {
    REACT("未知开放式问题，边想边做"),
    PLAN_AND_SOLVE("步骤固定的业务流程，先规划后执行"),
    SELF_REFLECTION("对结果准确性敏感的场景，执行后自检");

    private final String suitedFor;

    ReasoningMode(String suitedFor) { this.suitedFor = suitedFor; }

    public String getSuitedFor() { return suitedFor; }
}
