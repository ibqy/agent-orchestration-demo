package com.xb.agent.multi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 批量执行器 —— 结构化并发语义（fail-fast + 超时 + 结果聚合）
 *
 * <p><b>先说一个比 join() 更隐蔽的坑</b>：{@code CompletableFuture.allOf()}
 * 并不会因为某个子任务失败就提前完成——JDK 用树形组合实现，
 * 它要等<b>所有</b>任务都结束（无论成功失败）才抛异常。
 * 实测：3 个任务（快/慢 2s/抛异常），allOf().get() 整整等了 2 秒才抛
 * ExecutionException，"fail-fast" 名存实亡（复现脚本见提交说明）。</p>
 *
 * <p>正确做法是给每个 future 挂 {@code whenComplete}：任一失败立即
 * {@code cancel(true)} 其余。配合整体超时与逐任务结果聚合，
 * 就是结构化并发的三条标准语义：要么全部成功、要么失败即取消其余、要么整体超时。</p>
 *
 * <p>为什么不用 JDK 的 StructuredTaskScope：它到 JDK 24 仍是预览特性，
 * 启用要加 {@code --enable-preview}，教学项目不该让编译器告警满天飞。
 * 这里用 CompletableFuture 手工实现同样语义，代码量三十行。</p>
 *
 * <p>取消的局限（诚实声明）：{@code cancel(true)} 只是发起中断，
 * 阻塞在 LLM HTTP 调用里的子任务能否立即停下取决于底层 IO 是否响应中断；
 * 生产上应给每个子任务传取消令牌（CancellationToken）做真正的协作式取消。</p>
 *
 * @author ibqy
 */
@Component
public class BatchRunner {

    private static final Logger log = LoggerFactory.getLogger(BatchRunner.class);

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    /** 单任务执行结果（按批次下标定位） */
    public record Outcome<T>(int index, T value, Throwable error) {
        public boolean isSuccess() {
            return error == null;
        }
    }

    /** 批次执行结果 */
    public record BatchResult<T>(List<Outcome<T>> outcomes, boolean timedOut) {

        public static <T> BatchResult<T> empty() {
            return new BatchResult<>(List.of(), false);
        }

        /** 成功的值列表 */
        public List<T> successes() {
            return outcomes.stream().filter(Outcome::isSuccess).map(Outcome::value).toList();
        }

        /** 失败描述列表（"任务#i: 原因"，按批次下标排序） */
        public List<String> failures() {
            return outcomes.stream()
                    .filter(o -> !o.isSuccess())
                    .map(o -> "任务#" + o.index() + ": " + describe(o.error()))
                    .toList();
        }

        /** 失败原因（供调用方识别控制流异常，如 HITL 拒绝） */
        public List<Throwable> errors() {
            return outcomes.stream().map(Outcome::error).filter(Objects::nonNull).toList();
        }

        public boolean allSucceeded() {
            return outcomes.stream().allMatch(Outcome::isSuccess) && !timedOut;
        }

        public int total() {
            return outcomes.size();
        }

        private static String describe(Throwable error) {
            if (error == null) return "未知";
            String message = error.getMessage();
            return error.getClass().getSimpleName() +
                    (message == null || message.isBlank() ? "" : ": " + message);
        }
    }

    /**
     * 并行执行一批任务
     *
     * @param tasks   任务列表（每个任务在独立虚拟线程上执行）
     * @param timeout 整体超时；超时后取消未完成任务，已完成的保留
     * @return 聚合结果（按下标对齐，含成功值与失败原因）
     */
    public <T> BatchResult<T> runAll(List<Supplier<T>> tasks, Duration timeout) {
        if (tasks == null || tasks.isEmpty()) {
            return BatchResult.empty();
        }

        List<CompletableFuture<T>> futures = tasks.stream()
                .map(task -> CompletableFuture.supplyAsync(task, executor))
                .toList();

        // fail-fast 的关键：不等 allOf（它等全部完成才抛异常），
        // 每个 future 完成时检查，失败就立刻取消其余
        futures.forEach(f -> f.whenComplete((r, ex) -> {
            if (ex != null) {
                futures.forEach(other -> {
                    if (other != f) other.cancel(true);
                });
            }
        }));

        boolean timedOut = false;
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            timedOut = true;
            log.warn("[Batch] 批次超时({}ms)，取消未完成任务", timeout.toMillis());
            cancelAll(futures);
        } catch (ExecutionException e) {
            // 失败原因已由各 future 承载，级联取消也已由 whenComplete 完成，这里无需处理
            log.warn("[Batch] 批次内任务失败: {}", e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            cancelAll(futures);
        }

        return aggregate(futures, timedOut);
    }

    private void cancelAll(List<? extends CompletableFuture<?>> futures) {
        futures.forEach(f -> f.cancel(true));
    }

    private <T> BatchResult<T> aggregate(List<CompletableFuture<T>> futures, boolean timedOut) {
        List<Outcome<T>> outcomes = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            CompletableFuture<T> future = futures.get(i);
            if (future.isDone() && !future.isCompletedExceptionally() && !future.isCancelled()) {
                outcomes.add(new Outcome<>(i, future.join(), null));
            } else {
                outcomes.add(new Outcome<>(i, null, extractError(future)));
            }
        }
        return new BatchResult<>(outcomes, timedOut);
    }

    private Throwable extractError(CompletableFuture<?> future) {
        if (future.isCancelled()) {
            return new CancellationException("任务被取消（批次内其他任务失败或整体超时）");
        }
        try {
            future.join();
            return new IllegalStateException("未知失败");
        } catch (CancellationException e) {
            return e;
        } catch (Exception e) {
            return e.getCause() != null ? e.getCause() : e;
        }
    }
}
