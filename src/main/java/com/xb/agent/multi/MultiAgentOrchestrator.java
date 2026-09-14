package com.xb.agent.multi;

import com.xb.agent.reasoning.ToolInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 多 Agent 编排器 —— 串起 Planner / Worker / Reviewer
 *
 * 心得1: 无依赖子任务并行跑（虚拟线程池），有依赖的等前置完成
 * 心得2: Reviewer 判 RETRY 时最多重试 2 次，防止评审和执行互相拉扯死循环
 */
@Service
public class MultiAgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(MultiAgentOrchestrator.class);
    private static final int MAX_REVIEW_RETRIES = 2;

    private final PlannerAgent planner;
    private final WorkerAgent worker;
    private final ReviewerAgent reviewer;

    // 虚拟线程: 每个阻塞型 LLM 调用一个线程，成本极低
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public MultiAgentOrchestrator(PlannerAgent planner, WorkerAgent worker, ReviewerAgent reviewer) {
        this.planner = planner;
        this.worker = worker;
        this.reviewer = reviewer;
    }

    public String run(String userGoal, ToolInvoker toolInvoker) {
        String taskId = UUID.randomUUID().toString().substring(0, 8);
        TaskPlan plan = planner.createPlan(taskId, userGoal);

        while (!plan.allFinished()) {
            List<SubTask> batch = plan.runnableTasks();
            if (batch.isEmpty()) {
                // 剩余任务全部被依赖卡死 → 依赖成环或前置全失败
                log.error("[Orchestrator] 无可执行任务，终止. 未完成: {}",
                        plan.getSubTasks().stream()
                                .filter(t -> t.getStatus() == SubTask.Status.PENDING)
                                .map(SubTask::getId).toList());
                break;
            }

            // 并行执行本批子任务
            List<CompletableFuture<Void>> futures = batch.stream()
                    .map(task -> CompletableFuture.runAsync(
                            () -> executeAndReview(task, toolInvoker), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
        }

        return summarize(plan);
    }

    private void executeAndReview(SubTask task, ToolInvoker toolInvoker) {
        worker.execute(task, toolInvoker);

        // 评审循环
        while (task.getRetryCount() < MAX_REVIEW_RETRIES) {
            ReviewerAgent.Verdict verdict = reviewer.review(task);
            if (verdict == ReviewerAgent.Verdict.PASS) return;

            if (verdict == ReviewerAgent.Verdict.ABORT) {
                task.setStatus(SubTask.Status.FAILED);
                return;
            }
            // RETRY
            task.incrementRetry();
            task.setStatus(SubTask.Status.PENDING);
            worker.execute(task, toolInvoker);
        }
        task.setStatus(SubTask.Status.FAILED);
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
