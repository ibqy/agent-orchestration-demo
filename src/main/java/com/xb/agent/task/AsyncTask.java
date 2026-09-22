package com.xb.agent.task;

import java.util.UUID;

/**
 * 异步任务 —— 队列执行模型
 *
 * @author ibqy
 */
public class AsyncTask {

    public enum TaskStatus {
        PENDING, RUNNING, RETRYING, DEAD, SUCCESS
    }

    private String id;
    private String type;
    private String payload;
    private int retryCount;
    private int maxRetries;
    private TaskStatus status;

    public AsyncTask() {
        this.id = UUID.randomUUID().toString();
        this.retryCount = 0;
        this.status = TaskStatus.PENDING;
    }

    public AsyncTask(String type, String payload, int maxRetries) {
        this();
        this.type = type;
        this.payload = payload;
        this.maxRetries = maxRetries;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public int getRetryCount() { return retryCount; }
    public int getMaxRetries() { return maxRetries; }
    public TaskStatus getStatus() { return status; }

    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public void setStatus(TaskStatus status) { this.status = status; }

    /** 重试次数 +1（用于判断是否超过最大重试次数） */
    public void incrementRetryCount() {
        this.retryCount++;
    }
}
