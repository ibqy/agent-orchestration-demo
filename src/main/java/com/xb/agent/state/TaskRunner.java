package com.xb.agent.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 状态机运行器 —— 快照 ↔ FSM 桥接
 *
 * 心得：Spring Statemachine 本身是内存态，
 * 每次事件后必须手动同步快照，否则重启状态就丢
 */
@Service
public class TaskRunner {

    private static final Logger log = LoggerFactory.getLogger(TaskRunner.class);

    private final SnapshotStore snapshotStore;

    public TaskRunner(SnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    /** 发事件 + 持久化快照 */
    public boolean fireEvent(String taskId, AgentEvent event) {
        TaskSnapshot snapshot = snapshotStore.load(taskId);
        if (snapshot == null) {
            log.warn("[TaskRunner] 快照不存在 task={}", taskId);
            return false;
        }

        AgentState from = snapshot.getState();
        // 流转合法性表: 事件 → 目标状态
        AgentState target = transitionTarget(from, event);
        if (target == null) {
            log.warn("[TaskRunner] 非法流转 task={} state={} event={}", taskId, from, event);
            return false;
        }

        snapshot.setState(target);
        snapshotStore.save(snapshot);
        log.info("[TaskRunner] task={} {} --[{}]--> {}", taskId, from, event, target);
        return true;
    }

    private AgentState transitionTarget(AgentState from, AgentEvent event) {
        return switch (event) {
            case START -> from == AgentState.INIT ? AgentState.PLANNING : null;
            case PLAN_DONE -> from == AgentState.PLANNING ? AgentState.RUNNING : null;
            case TASK_DONE -> from == AgentState.RUNNING ? AgentState.REVIEW : null;
            case TASK_FAILED -> from == AgentState.RUNNING ? AgentState.FAILED : null;
            case REVIEW_PASS -> from == AgentState.REVIEW ? AgentState.SUCCESS : null;
            case REVIEW_REJECT -> from == AgentState.REVIEW ? AgentState.RUNNING : null;
            case NEED_HUMAN -> from == AgentState.RUNNING ? AgentState.WAIT_HUMAN : null;
            case HUMAN_CONFIRM -> from == AgentState.WAIT_HUMAN ? AgentState.RUNNING : null;
            case HUMAN_ABORT -> from == AgentState.WAIT_HUMAN ? AgentState.FAILED : null;
        };
    }

    /** 断点续跑: 读快照，返回当前状态 */
    public AgentState resume(String taskId) {
        TaskSnapshot snapshot = snapshotStore.load(taskId);
        if (snapshot == null) return null;
        log.info("[TaskRunner] 恢复任务 {} 从状态 {}", taskId, snapshot.getState());
        return snapshot.getState();
    }
}
