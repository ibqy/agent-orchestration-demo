package com.xb.agent.multi;

import com.xb.agent.guard.HITLRejectedException;
import com.xb.agent.reasoning.ReasoningMode;
import com.xb.agent.reasoning.StepListener;
import com.xb.agent.reasoning.ToolInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 多 Agent 编排器 —— 串起 Planner / Worker / Reviewer
 *
 * 心得1: 无依赖子任务并行跑（虚拟线程），有依赖的等前置完成
 * 心得2: Reviewer 判 RETRY 时最多重试 2 次，防止评审和执行互相拉扯死循环
 * 心得3: 并行批次交给 {@link BatchRunner} 而不是裸 allOf().join()——
 *         fail-fast/超时/结果聚合是结构化并发的标准语义（见 BatchRunner 注释）
 *
 * @author ibqy
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);
    private static final int MAX_REVIEW_RETRIES = 2;

    /** 单批次整体超时：LLM 调用可能慢，但不能无限等 */
    private static final Duration BATCH_TIMEOUT = Duration.ofMinutes(5);

    private final PlannerAgent planner;
    private final WorkerAgent worker;
    private final ReviewerAgent reviewer;
    private final BatchRunner batchRunner;

    public MultiAgentOrchestrator(PlannerAgent planner, WorkerAgent worker,
                                  ReviewerAgent reviewer, BatchRunner batchRunner) {
        this.planner = planner;
        this.worker = worker;
        this.reviewer = reviewer;
        this.batchRunner = batchRunner;
    }

    /**
     * 执行多 Agent 编排主流程
     *
     * @param userGoal     用户目标描述
     * @param toolInvoker  工具调用回调（含防护管道）
     * @param mode         推理模式（传给每个 Worker，null = 配置默认）
     * @param listener     步骤监听器（SSE 推送，可为 noop）
     * @return 汇总后的执行结果文本
     */
    public String run(String userGoal, ToolInvoker toolInvoker, ReasoningMode mode, StepListener listener) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        TaskPlan plan = planner.createPlan(taskId, userGoal);
        listener.onStep("PLAN", "任务拆解为 " + plan.getSubTasks().size() + " 个子任务");

        while (!plan.allFinished()) {
            List<SubTask> batch = plan.runnableTasks();
            if (batch.isEmpty()) {
                // 剩余任务全部被依赖卡死 → 依赖成环或前置全失败
                log.error("[Orchestrator] 无可执行任务，终止. 未完成: {}",
                        plan.getSubTasks().stream()
                                .filter(t -> t.getStatus() == SubTask.Status.PENDING)
                                .map(SubTask::getId).toList());
                listener.onStep("ERROR", "剩余子任务被依赖卡死（依赖成环或前置失败）");
                break;
            }

            // 并行执行本批子任务（fail-fast + 超时 + 聚合）
            List<Supplier<Void>> jobs = batch.stream()
                    .<Supplier<Void>>map(task -> () -> {
                        executeAndReview(task, toolInvoker, mode, listener);
                        return null;
                    })
                    .toList();

            BatchRunner.BatchResult<Void> result = batchRunner.runAll(jobs, BATCH_TIMEOUT);
            if (!result.allSucceeded()) {
                log.warn("[Orchestrator] 批次未全部成功: {}", result.failures());
                listener.onStep("BATCH", "本批 " + result.total() + " 个子任务中 " +
                        result.failures().size() + " 个未完成");
            }

            // HITL 拒绝是"任务级终止信号"，不能当作普通子任务失败被汇总吞掉——
            // 否则人工 abort 之后，任务还会把剩余子任务跑完并标记 SUCCESS，
            // 人工关口形同虚设。识别到就向上抛，穿透到 AgentTaskService 收敛 FAILED
            for (Throwable error : result.errors()) {
                if (error instanceof HITLRejectedException rejected) {
                    throw rejected;
                }
            }
        }

        return summarize(plan);
    }

    private void executeAndReview(SubTask task, ToolInvoker toolInvoker,
                                  ReasoningMode mode, StepListener listener) {
        worker.execute(task, toolInvoker, mode, listener);

        // 评审循环
        while (task.getRetryCount() < MAX_REVIEW_RETRIES) {
            ReviewerAgent.Verdict verdict = reviewer.review(task);
            if (verdict == ReviewerAgent.Verdict.PASS) {
                listener.onStep("REVIEW", "子任务 " + task.getId() + " 评审通过");
                return;
            }

            if (verdict == ReviewerAgent.Verdict.ABORT) {
                task.setStatus(SubTask.Status.FAILED);
                listener.onStep("REVIEW", "子任务 " + task.getId() + " 评审中止");
                return;
            }
            // RETRY
            task.incrementRetry();
            task.setStatus(SubTask.Status.PENDING);
            listener.onStep("REVIEW", "子任务 " + task.getId() + " 评审打回，第 " +
                    task.getRetryCount() + " 次重试");
            worker.execute(task, toolInvoker, mode, listener);
        }
        task.setStatus(SubTask.Status.FAILED);
        listener.onStep("REVIEW", "子任务 " + task.getId() + " 重试次数用尽，标记失败");
    }

    private String summarize(TaskPlan plan) {
        List<String> parts = new ArrayList<>();
        for (SubTask t : plan.getSubTasks()) {
            parts.add("[" + t.getStatus() + "] " + t.getGoal() + " → " +
                    (t.getResult() == null ? "无结果" : t.getResult()));
        }
        return "任务 " + plan.getTaskId() + "（" + plan.getUserGoal() + "）执行结果:\n" +
                String.join("\n", parts);
    }
}
