package com.xb.agent.reasoning;

/**
 * 推理 Agent 抽象
 *
 * 心得：三种模式本质都是"循环 + 终止条件"的差异，
 * 统一抽象后可以运行时切换
 */
public interface ReasoningAgent {

    /**
     * 执行任务
     * @param goal 任务目标
     * @return 最终回答
     */
    String execute(String goal);
}
