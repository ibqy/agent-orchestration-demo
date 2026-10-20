package com.xb.agent.state;

import com.xb.agent.reasoning.ReasoningMode;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务快照 —— 断点续跑的全部状态
 *
 * 心得：快照字段 = 恢复执行所需的全部信息。
 * 落一个字段，重启后就恢复不回来，设计时按"重启后我要知道什么"反推
 *
 * <p>result / reasoningMode 是补课的字段：早期只把 state/steps/tokenUsed 落盘，
 * 任务跑完结果只存在于 HTTP 响应里——客户端断线就永远拿不到结果，
 * "查快照"接口也看不到任何产出。现在结果直接落快照，查询接口原样返回。</p>
 *
 * @author ibqy
 */
public class TaskSnapshot {

    private String taskId;
    private String userId;
    private String tenantId;
    private String userGoal;

    private AgentState state;
    private String planJson;                    // 任务计划序列化
    private List<String> completedSubTaskIds = new ArrayList<>();
    private List<StepRecord> steps = new ArrayList<>();  // 执行轨迹
    private long tokenUsed = 0;
    private long updatedAt;

    /** 最终结果文本（任务成功/失败后写入，供查询接口返回） */
    private String result;

    /** 本次任务使用的推理模式（复盘/审计用） */
    private ReasoningMode reasoningMode;

    /** 单步执行记录: thought / 工具入参 / observation */
    public record StepRecord(String subTaskId, String thought, String toolName,
                             String toolArgs, String observation, long timestamp) {}

    public TaskSnapshot() { this.updatedAt = System.currentTimeMillis(); }

    public TaskSnapshot(String taskId, String userId, String tenantId, String userGoal) {
        this.taskId = taskId;
        this.userId = userId;
        this.tenantId = tenantId;
        this.userGoal = userGoal;
        this.state = AgentState.INIT;
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 追加一条执行步骤记录
     * @param step 步骤记录
     */
    public void addStep(StepRecord step) {
        steps.add(step);
        this.updatedAt = System.currentTimeMillis();
    }

    /**
     * 累加 token 消耗
     * @param n 本次消耗的 token 数
     */
    public void addToken(long n) { tokenUsed += n; }

    // getter / setter
    public String getTaskId() { return taskId; }
    public String getUserId() { return userId; }
    public String getTenantId() { return tenantId; }
    public String getUserGoal() { return userGoal; }
    public AgentState getState() { return state; }
    public String getPlanJson() { return planJson; }
    public List<String> getCompletedSubTaskIds() { return completedSubTaskIds; }
    public List<StepRecord> getSteps() { return steps; }
    public long getTokenUsed() { return tokenUsed; }
    public long getUpdatedAt() { return updatedAt; }
    public String getResult() { return result; }
    public ReasoningMode getReasoningMode() { return reasoningMode; }

    public void setState(AgentState state) { this.state = state; this.updatedAt = System.currentTimeMillis(); }
    public void setPlanJson(String planJson) { this.planJson = planJson; }
    public void setUserId(String userId) { this.userId = userId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public void setResult(String result) { this.result = result; this.updatedAt = System.currentTimeMillis(); }
    public void setReasoningMode(ReasoningMode reasoningMode) { this.reasoningMode = reasoningMode; }
}
