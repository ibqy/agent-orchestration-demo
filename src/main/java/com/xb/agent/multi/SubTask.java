package com.xb.agent.multi;

import java.util.ArrayList;
import java.util.List;

/** 子任务 —— Planner 拆解产物，Worker 执行单元 */
public class SubTask {

    /** 状态: 待执行 / 执行中 / 完成 / 失败 / 待重试 */
    public enum Status { PENDING, RUNNING, DONE, FAILED, RETRY }

    private final String id;
    private String goal;                 // 子任务目标
    private List<String> dependsOn = new ArrayList<>();  // 依赖的其他子任务 id
    private Status status = Status.PENDING;
    private String result;              // 执行结果
    private int retryCount = 0;

    public SubTask(String id, String goal) {
        this.id = id;
        this.goal = goal;
    }

    public boolean readyToRun(List<String> completedIds) {
        return completedIds.containsAll(dependsOn);
    }

    public String getId() { return id; }
    public String getGoal() { return goal; }
    public List<String> getDependsOn() { return dependsOn; }
    public Status getStatus() { return status; }
    public String getResult() { return result; }
    public int getRetryCount() { return retryCount; }

    public void setGoal(String goal) { this.goal = goal; }
    public void setDependsOn(List<String> dependsOn) { this.dependsOn = dependsOn; }
    public void setStatus(Status status) { this.status = status; }
    public void setResult(String result) { this.result = result; }
    public void incrementRetry() { retryCount++; }
}
