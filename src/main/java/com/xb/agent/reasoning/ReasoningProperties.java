package com.xb.agent.reasoning;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 推理模式配置 —— 从 application.yml agent.reasoning.* 绑定
 *
 * <p>心得：三种推理模式（ReAct / Plan-and-Solve / Self-Reflection）不是"哪个更好"的关系，
 * 而是"任务类型决定模式"。默认 ReAct，因为开放性问题没有预设流程；
 * 业务流程类任务换 PLAN_AND_SOLVE，准确性敏感场景套 SELF_REFLECTION。
 * 允许运行时按请求覆盖（{@code mode} 参数），是因为同一服务可能同时服务两类任务。</p>
 *
 * @author ibqy
 */
@ConfigurationProperties(prefix = "agent.reasoning")
public class ReasoningProperties {

    /** 默认推理模式 */
    private ReasoningMode mode = ReasoningMode.REACT;

    /** ReAct / Plan-and-Solve 的最大步数 */
    private int maxSteps = 5;

    /** Self-Reflection 的最大自检重试次数 */
    private int maxReflectionRetries = 2;

    public ReasoningMode getMode() { return mode; }
    public void setMode(ReasoningMode mode) { this.mode = mode; }
    public int getMaxSteps() { return maxSteps; }
    public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
    public int getMaxReflectionRetries() { return maxReflectionRetries; }
    public void setMaxReflectionRetries(int maxReflectionRetries) { this.maxReflectionRetries = maxReflectionRetries; }
}
