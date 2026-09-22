package com.xb.agent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 任务发布器 —— 管理异步任务队列
 *
 * @author ibqy
 */
@Component
public class TaskPublisher {

    private static final Logger log = LoggerFactory.getLogger(TaskPublisher.class);

    private final Queue<AsyncTask> queue = new ConcurrentLinkedQueue<>();

    /** 发布新任务 */
    public void publish(String type, String payload, int maxRetries) {
        AsyncTask task = new AsyncTask(type, payload, maxRetries);
        queue.add(task);
        log.info("[TaskPublisher] 发布新任务 id={} type={}", task.getId(), type);
    }

    /**
     * 重新入队已存在的任务（重试时使用）
     * Fix: 直接复用原任务对象，避免创建新 UUID 导致原任务永远卡在 RETRYING
     */
    public void requeue(AsyncTask task) {
        queue.add(task);
        log.info("[TaskPublisher] 任务重新入队 id={} retryCount={}", task.getId(), task.getRetryCount());
    }

    /**
     * 取出队首任务（非阻塞）
     * @return 队首任务，队列为空返回 null
     */
    public AsyncTask poll() {
        return queue.poll();
    }

    /**
     * 当前队列中待处理任务数量
     * @return 队列长度
     */
    public int size() {
        return queue.size();
    }
}
