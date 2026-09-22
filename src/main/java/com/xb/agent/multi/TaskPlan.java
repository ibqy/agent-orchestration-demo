package com.xb.agent.multi;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务计划 —— Planner 输出，含全部子任务和依赖关系
 *
 * <p>提供可运行任务查询和完成状态判断，驱动多 Agent 协作编排。</p>
 *
 * @author ibqy
 */
public class TaskPlan {

    private final String taskId;
    private final String userGoal;
    private final List<SubTask> subTasks = new ArrayList<>();

    public TaskPlan(String taskId, String userGoal) {
        this.taskId = taskId;
        this.userGoal = userGoal;
    }

    /**
     * 添加一个子任务到计划中
     * @param task 子任务
     */
    public void addSubTask(SubTask task) {
        subTasks.add(task);
    }

    /**
     * 获取已完成子任务的ID列表
     * @return 已完成子任务ID列表
     */
    public List<String> completedIds() {
        return subTasks.stream()
                .filter(t -> t.getStatus() == SubTask.Status.DONE)
                .map(SubTask::getId)
                .toList();
    }

    /**
     * 获取当前可执行的子任务（依赖已满足且未开始）
     * @return 可立即执行的子任务列表
     */
    public List<SubTask> runnableTasks() {
        List<String> completed = completedIds();
        return subTasks.stream()
                .filter(t -> t.getStatus() == SubTask.Status.PENDING)
                .filter(t -> t.readyToRun(completed))
                .toList();
    }

    /**
     * 判断所有子任务是否已结束（完成或失败）
     * @return true 表示无待执行任务
     */
    public boolean allFinished() {
        return subTasks.stream().allMatch(t ->
                t.getStatus() == SubTask.Status.DONE || t.getStatus() == SubTask.Status.FAILED);
    }

    public String getTaskId() { return taskId; }
    public String getUserGoal() { return userGoal; }
    public List<SubTask> getSubTasks() { return subTasks; }
}
