package com.xb.agent.multi;

import java.util.ArrayList;
import java.util.List;

/** 任务计划 —— Planner 输出，含全部子任务和依赖关系 */
public class TaskPlan {

    private final String taskId;
    private final String userGoal;
    private final List<SubTask> subTasks = new ArrayList<>();

    public TaskPlan(String taskId, String userGoal) {
        this.taskId = taskId;
        this.userGoal = userGoal;
    }

    public void addSubTask(SubTask task) {
        subTasks.add(task);
    }

    /** 已完成子任务 id 列表 */
    public List<String> completedIds() {
        return subTasks.stream()
                .filter(t -> t.getStatus() == SubTask.Status.DONE)
                .map(SubTask::getId)
                .toList();
    }

    /** 当前可执行的子任务（依赖满足且未开始） */
    public List<SubTask> runnableTasks() {
        List<String> completed = completedIds();
        return subTasks.stream()
                .filter(t -> t.getStatus() == SubTask.Status.PENDING)
                .filter(t -> t.readyToRun(completed))
                .toList();
    }

    public boolean allFinished() {
        return subTasks.stream().allMatch(t ->
                t.getStatus() == SubTask.Status.DONE || t.getStatus() == SubTask.Status.FAILED);
    }

    public String getTaskId() { return taskId; }
    public String getUserGoal() { return userGoal; }
    public List<SubTask> getSubTasks() { return subTasks; }
}
