package com.xb.agent.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 人工审批门测试 —— HITL 的"暂停/放行"语义
 *
 * 覆盖三个关键时序：先登记后流转（不丢审批）、批准唤醒、拒绝/超时收敛。
 *
 * @author ibqy
 */
@DisplayName("ApprovalGate 人工审批门")
class ApprovalGateTest {

    private final ApprovalGate gate = new ApprovalGate();

    @Test
    @DisplayName("批准：await 返回 true，任务继续执行")
    void confirmReleasesWaitingTask() {
        var future = gate.request("t1", "deleteOrder", "{\"id\":\"A1001\"}");
        Thread.startVirtualThread(() -> {
            sleep(50);
            assertThat(gate.confirm("t1")).isTrue();
        });

        assertThat(gate.await("t1", future, Duration.ofSeconds(5))).isTrue();
        assertThat(gate.pendingApprovals()).isEmpty();
    }

    @Test
    @DisplayName("拒绝：await 返回 false，调用方应终止任务")
    void abortRejectsWaitingTask() {
        var future = gate.request("t1", "deleteOrder", "{}");
        Thread.startVirtualThread(() -> {
            sleep(50);
            assertThat(gate.abort("t1")).isTrue();
        });

        assertThat(gate.await("t1", future, Duration.ofSeconds(5))).isFalse();
    }

    @Test
    @DisplayName("先登记再 confirm 不会丢审批（竞态时序）")
    void confirmBeforeAwaitIsNotLost() {
        // 模拟审批请求抢先到达：request 之后、await 之前就 confirm
        var future = gate.request("t1", "refund", "{}");
        assertThat(gate.confirm("t1")).isTrue();

        // await 立即返回 true，不会白等到超时
        assertThat(gate.await("t1", future, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    @DisplayName("超时按拒绝处理，且待审批清单被清理")
    void timeoutIsRejection() {
        var future = gate.request("t1", "refund", "{}");
        assertThat(gate.await("t1", future, Duration.ofMillis(100))).isFalse();
        assertThat(gate.pendingApprovals()).isEmpty();
    }

    @Test
    @DisplayName("没有待审批任务时 confirm/abort 返回 false（不炸）")
    void confirmWithoutPendingIsSafe() {
        assertThat(gate.confirm("nonexistent")).isFalse();
        assertThat(gate.abort("nonexistent")).isFalse();
    }

    @Test
    @DisplayName("待审批清单包含任务/工具/参数快照")
    void pendingApprovalSnapshot() {
        gate.request("t1", "deleteOrder", "{\"id\":\"A1001\"}");

        var pending = gate.pendingApprovals();
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).taskId()).isEqualTo("t1");
        assertThat(pending.get(0).toolName()).isEqualTo("deleteOrder");
        assertThat(pending.get(0).argsJson()).contains("A1001");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
