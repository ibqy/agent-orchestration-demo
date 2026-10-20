package com.xb.agent.state;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行步骤事件总线 —— Agent 黑盒的"透视窗"
 *
 * <p>心得：日志解决"开发者事后排查"，事件总线解决"用户实时看到 Agent 在干什么"。
 * 推理循环通过 {@code StepListener} 抛事件，这里按任务分发：
 * WebFlux 的 SSE 端点订阅 {@link #subscribe(String)}，前端就能逐条看到
 * THOUGHT / ACTION / OBSERVATION / HITL / RESULT。</p>
 *
 * <p>选型：{@code Sinks.many().replay().limit(N)} 而不是 unicast——
 * replay 让迟到订阅者也能拿到最近 N 条（客户端断线重连、轮询跳转场景），
 * limit 防止长任务把内存吃光；complete 后条目保留一段时间供回放，过期清理。</p>
 *
 * @author ibqy
 */
@Component
public class StepEventBus {

    private static final Logger log = LoggerFactory.getLogger(StepEventBus.class);

    /** 每个任务回放的事件条数上限 */
    private static final int REPLAY_LIMIT = 200;

    /** 任务结束后事件保留时长（供迟到订阅者回放） */
    private static final Duration RETAIN_AFTER_COMPLETE = Duration.ofMinutes(30);

    /** 单条执行步骤事件 */
    public record StepEvent(String taskId, String type, String message, long timestamp) {
        public static StepEvent of(String taskId, String type, String message) {
            return new StepEvent(taskId, type, message, System.currentTimeMillis());
        }
    }

    private final Map<String, Entry> tasks = new ConcurrentHashMap<>();

    private record Entry(Sinks.Many<StepEvent> sink, long completedAt) {
        boolean isComplete() { return completedAt > 0; }
    }

    /** 任务提交时注册（之后 publish/complete 都按 taskId 找到它） */
    public void register(String taskId) {
        tasks.put(taskId, new Entry(Sinks.many().replay().limit(REPLAY_LIMIT), 0));
        log.debug("[StepBus] 注册任务事件通道 task={}", taskId);
    }

    /** 发布一条步骤事件（订阅者不存在时静默丢弃——事件总线不该拖慢执行线程） */
    public void publish(String taskId, String type, String message) {
        Entry entry = tasks.get(taskId);
        if (entry == null) return;
        entry.sink().tryEmitNext(StepEvent.of(taskId, type, message));
    }

    /** 任务终结（成功/失败/中止）——订阅方收到 onComplete，SSE 连接关闭 */
    public void complete(String taskId) {
        Entry entry = tasks.get(taskId);
        if (entry == null || entry.isComplete()) return;
        entry.sink().tryEmitComplete();
        tasks.put(taskId, new Entry(entry.sink(), System.currentTimeMillis()));
        log.debug("[StepBus] 任务事件通道关闭 task={}", taskId);
    }

    /**
     * 订阅任务的步骤事件流（含历史回放）
     *
     * @param taskId 任务ID
     * @return 事件流；任务不存在时返回单条 NOT_FOUND 事件后立即结束
     */
    public Flux<StepEvent> subscribe(String taskId) {
        Entry entry = tasks.get(taskId);
        if (entry == null) {
            return Flux.just(StepEvent.of(taskId, "NOT_FOUND", "任务不存在或事件通道已清理"));
        }
        return entry.sink().asFlux();
    }

    /** 当前活跃（未结束）的任务通道数（监控/测试用） */
    public int activeChannels() {
        return (int) tasks.values().stream().filter(e -> !e.isComplete()).count();
    }
}
