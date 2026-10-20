package com.xb.agent.multi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量执行器测试 —— 结构化并发语义
 *
 * 对照 {@code allOf().join()} 的三个缺陷逐个验证修复：
 * 失败不丢结果、失败即取消其余、整体超时可控。
 *
 * @author ibqy
 */
@DisplayName("BatchRunner 批量执行器")
class BatchRunnerTest {

    private final BatchRunner runner = new BatchRunner();

    @Test
    @DisplayName("全部成功：聚合全部结果")
    void allSucceed() {
        var result = runner.runAll(List.of(
                () -> "a",
                () -> "b",
                () -> "c"), Duration.ofSeconds(5));

        assertThat(result.allSucceeded()).isTrue();
        assertThat(result.successes()).containsExactly("a", "b", "c");
        assertThat(result.failures()).isEmpty();
        assertThat(result.timedOut()).isFalse();
    }

    @Test
    @DisplayName("一个失败：fail-fast 取消其余，且已完成的结果不丢")
    void failFastKeepsFinishedResults() {
        var started = new CopyOnWriteArrayList<String>();
        // 显式声明 List<Supplier<Void>>：混合 lambda 与强制类型转换时，
        // List.of 的泛型推断会把目标类型定成 Void，String lambda 直接编译失败
        List<java.util.function.Supplier<Void>> tasks = new java.util.ArrayList<>();
        tasks.add(() -> {
            started.add("fast");
            return null;                       // 立即成功
        });
        tasks.add(() -> {
            started.add("slow");
            sleep(2000);                        // 慢任务，应被级联取消
            return null;
        });
        tasks.add(() -> {
            throw new IllegalStateException("boom");
        });

        long start = System.currentTimeMillis();
        var result = runner.runAll(tasks, Duration.ofSeconds(5));
        long elapsed = System.currentTimeMillis() - start;

        assertThat(result.allSucceeded()).isFalse();
        // 按下标对齐：0 成功、1 被取消、2 抛异常
        assertThat(result.outcomes().get(0).isSuccess()).isTrue();
        assertThat(result.outcomes().get(1).error()).isInstanceOf(CancellationException.class);
        assertThat(result.outcomes().get(2).error()).hasMessage("boom");
        assertThat(result.failures()).hasSize(2);
        assertThat(result.errors()).hasSize(2);
        // fail-fast 生效：不会等慢任务跑满 2 秒（留 1s 余量防环境抖动）
        assertThat(elapsed).isLessThan(1000);
        assertThat(started).contains("fast", "slow");
    }

    @Test
    @DisplayName("整体超时：未完成任务取消，已完成保留，timedOut 标记")
    void timeoutCancelsUnfinished() {
        List<java.util.function.Supplier<Void>> tasks = new java.util.ArrayList<>();
        tasks.add(() -> null);                 // 立即完成
        tasks.add(() -> {
            sleep(5000);                       // 超时后被取消
            return null;
        });

        var result = runner.runAll(tasks, Duration.ofMillis(300));

        assertThat(result.timedOut()).isTrue();
        assertThat(result.allSucceeded()).isFalse();
        assertThat(result.successes()).hasSize(1);   // 已完成的结果保留
        assertThat(result.failures()).hasSize(1);
        assertThat(result.outcomes().get(1).error()).isInstanceOf(CancellationException.class);
    }

    @Test
    @DisplayName("控制流异常可被识别（HITL 拒绝穿透的数据基础）")
    void fatalErrorsAreExposedToCaller() {
        var result = runner.runAll(List.of(
                (java.util.function.Supplier<Void>) () -> {
                    throw new com.xb.agent.guard.HITLRejectedException("deleteOrder", "已拒绝");
                }), Duration.ofSeconds(5));

        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0)).isInstanceOf(com.xb.agent.guard.HITLRejectedException.class);
        assertThat(result.failures().get(0)).contains("deleteOrder");
    }

    @Test
    @DisplayName("空批次：直接返回空结果，不起线程")
    void emptyBatch() {
        var result = runner.runAll(List.of(), Duration.ofSeconds(1));
        assertThat(result.total()).isZero();
        assertThat(result.allSucceeded()).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
