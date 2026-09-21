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

    /**
     * 断点续跑: 读快照 → 恢复状态 → 重新提交执行
     *
     * 局限: 真正的续跑需要将任务重新提交给 MultiAgentOrchestrator 执行，
     * 但 TaskRunner 不持有 orchestrator 引用（避免循环依赖）。
     * 因此这里只能做到: 加载快照 → 流转到 RUNNING → 由调用方（AgentTaskService）
     * 负责重新编排执行。生产环境可注入 ApplicationEventPublisher 发事件解耦。
     */
    public AgentState resume(String taskId) {
        TaskSnapshot snapshot = snapshotStore.load(taskId);
        if (snapshot == null) return null;

        AgentState state = snapshot.getState();
        log.info("[TaskRunner] 恢复任务 {} 从状态 {}", taskId, state);

        // 如果任务处于等待人工确认状态，自动流转到 RUNNING
        if (state == AgentState.WAIT_HUMAN) {
            boolean ok = fireEvent(taskId, AgentEvent.HUMAN_CONFIRM);
            if (ok) {
                log.info("[TaskRunner] 任务 {} 已从 WAIT_HUMAN 恢复到 RUNNING", taskId);
                return AgentState.RUNNING;
            }
            log.warn("[TaskRunner] 任务 {} 状态流转失败", taskId);
        }

        return state;
    }
}
