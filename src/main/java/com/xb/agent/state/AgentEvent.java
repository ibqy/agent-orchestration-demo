package com.xb.agent.state;

/**
 * FSM 事件枚举 —— 状态流转触发器
 *
 * <p>驱动 Agent 任务状态机在 INIT→PLANNING→RUNNING→REVIEW→SUCCESS/FAILED 之间转换。</p>
 *
 * @author ibqy
 */
public enum AgentEvent {
    START,           // 任务启动: INIT → PLANNING
    PLAN_DONE,       // 规划完成: PLANNING → RUNNING
    TASK_DONE,       // 全部子任务完成: RUNNING → REVIEW
    TASK_FAILED,     // 子任务失败: RUNNING → FAILED
    REVIEW_PASS,     // 评审通过: REVIEW → SUCCESS
    REVIEW_REJECT,   // 评审不通过: REVIEW → RUNNING（重跑）
    NEED_HUMAN,      // 高危操作: RUNNING → WAIT_HUMAN
    HUMAN_CONFIRM,   // 人工确认: WAIT_HUMAN → RUNNING
    HUMAN_ABORT;     // 人工终止: WAIT_HUMAN → FAILED
}
