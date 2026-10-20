package com.xb.agent.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 人工审批门 —— HITL 的"暂停/放行"闸门
 *
 * <p>心得：暂停一个正在跑的 Agent，本质是"把执行线程 park 在一个
 * 能被外部唤醒的地方"。CompletableFuture 就是这根唤醒绳：
 * Agent 线程 {@link #await} 阻塞等待，/confirm 端点 {@link #confirm}
 * 完成 future 唤醒它，/abort {@link #abort} 以异常结果唤醒。</p>
 *
 * <p>两个关键时序设计：</p>
 * <ol>
 *   <li><b>先登记再流转</b>：{@code request()} 必须在状态流转到 WAIT_HUMAN
 *       之前调用——否则审批请求抢先到达时找不到登记记录，任务会永远 park 到超时。</li>
 *   <li><b>超时即拒绝</b>：等待有上限（默认 10 分钟，见 HitlProperties）。
 *       没有超时的人工通道等于一个可以把任务无限期挂起的后门。</li>
 * </ol>
 *
 * @author ibqy
 */
@Component
public class ApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(ApprovalGate.class);

    /** 待审批工单 */
    public record PendingApproval(String taskId, String toolName, String argsJson, long requestedAt) {}

    private final Map<String, CompletableFuture<Boolean>> decisions = new ConcurrentHashMap<>();
    private final Map<String, PendingApproval> pending = new ConcurrentHashMap<>();

    /**
     * 登记一条审批等待（必须在状态流转到 WAIT_HUMAN 之前调用）
     *
     * @return 决策 future：true=批准，false=拒绝/超时
     */
    public CompletableFuture<Boolean> request(String taskId, String toolName, String argsJson) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();
        decisions.put(taskId, future);
        pending.put(taskId, new PendingApproval(taskId, toolName, argsJson, System.currentTimeMillis()));
        log.info("[Approval] 待审批 task={} tool={}", taskId, toolName);
        return future;
    }

    /**
     * 阻塞等待审批结果
     *
     * @param future   {@link #request} 返回的决策 future
     * @param timeout  超时时长
     * @return true=批准继续执行；false=拒绝或超时（调用方应中止任务）
     */
    public boolean await(String taskId, CompletableFuture<Boolean> future, Duration timeout) {
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            log.warn("[Approval] 审批超时 task={}，按拒绝处理", taskId);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("[Approval] 等待被中断 task={}，按拒绝处理", taskId);
            return false;
        } catch (ExecutionException e) {
            log.warn("[Approval] 审批异常 task={}", taskId, e);
            return false;
        } finally {
            cleanup(taskId);
        }
    }

    /** 人工批准：唤醒等待中的任务线程 */
    public boolean confirm(String taskId) {
        CompletableFuture<Boolean> future = decisions.get(taskId);
        if (future == null) {
            log.warn("[Approval] 没有待审批任务 task={}", taskId);
            return false;
        }
        boolean released = future.complete(true);
        pending.remove(taskId);
        log.info("[Approval] 已批准 task={}", taskId);
        return released;
    }

    /** 人工拒绝：以 false 唤醒等待中的任务线程（任务将收敛到 FAILED） */
    public boolean abort(String taskId) {
        CompletableFuture<Boolean> future = decisions.get(taskId);
        if (future == null) {
            log.warn("[Approval] 没有待审批任务 task={}", taskId);
            return false;
        }
        boolean released = future.complete(false);
        pending.remove(taskId);
        log.info("[Approval] 已拒绝 task={}", taskId);
        return released;
    }

    /** 当前待审批清单（GET /agent/approvals 用） */
    public List<PendingApproval> pendingApprovals() {
        return List.copyOf(pending.values());
    }

    private void cleanup(String taskId) {
        decisions.remove(taskId);
        pending.remove(taskId);
    }
}
