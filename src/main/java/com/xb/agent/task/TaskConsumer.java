package com.xb.agent.task;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 任务消费者 —— 从队列取任务并执行
 */
@Component
public class TaskConsumer {

    private static final Logger log = LoggerFactory.getLogger(TaskConsumer.class);

    private final TaskPublisher publisher;

    public TaskConsumer(TaskPublisher publisher) {
        this.publisher = publisher;
    }

    /** 消费单个任务 */
    public void consume(AsyncTask task) {
        try {
            task.setStatus(AsyncTask.TaskStatus.RUNNING);
            log.info("[TaskConsumer] 执行任务 id={} type={}", task.getId(), task.getType());

            // 模拟执行逻辑
            executeTask(task);

            task.setStatus(AsyncTask.TaskStatus.SUCCESS);
            log.info("[TaskConsumer] 任务成功 id={}", task.getId());

        } catch (Exception e) {
            log.error("[TaskConsumer] 任务失败 id={}", task.getId(), e);

            // Fix: 重试时使用 requeue 复用原任务，而非 publish 创建新任务
            if (task.getRetryCount() < task.getMaxRetries()) {
                task.setStatus(AsyncTask.TaskStatus.RETRYING);
                task.incrementRetryCount();
                publisher.requeue(task);
            } else {
                task.setStatus(AsyncTask.TaskStatus.DEAD);
            }
        }
    }

    private void executeTask(AsyncTask task) {
        // 实际执行逻辑占位
    }
}
