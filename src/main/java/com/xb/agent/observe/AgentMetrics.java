package com.xb.agent.observe;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 运行指标 —— 学习项目用内存计数，生产接 Micrometer
 *
 * 心得：评估 Agent 好坏看这几个:
 *   任务完成率 / 平均耗时 / 工具失败率 / 不必要调用次数 / HITL 触发率
 * "不必要调用"= Agent 幻觉式调用不存在的工具，这个指标最能反映 prompt 质量
 */
@Component
public class AgentMetrics {

    private final AtomicLong totalTasks = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong failedToolCalls = new AtomicLong();
    private final AtomicLong hallucinatedToolCalls = new AtomicLong();  // 调用不存在的工具
    private final AtomicLong hitlCount = new AtomicLong();

    /** 工具维度失败计数 */
    private final Map<String, AtomicLong> toolFailures = new ConcurrentHashMap<>();

    public void taskStarted() { totalTasks.incrementAndGet(); }
    public void taskCompleted() { completedTasks.incrementAndGet(); }
    public void taskFailed() { failedTasks.incrementAndGet(); }
    public void hitlTriggered() { hitlCount.incrementAndGet(); }
    public void toolCalled() { toolCalls.incrementAndGet(); }
    public void toolFailed(String toolName) {
        failedToolCalls.incrementAndGet();
        toolFailures.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
    }
    public void hallucinatedTool() { hallucinatedToolCalls.incrementAndGet(); }

    public String report() {
        long total = totalTasks.get();
        long calls = toolCalls.get();
        return """
                === Agent 指标 ===
                任务总数: %d / 完成: %d / 失败: %d（完成率 %.1f%%）
                工具调用: %d / 失败: %d（失败率 %.1f%%）/ 幻觉调用: %d
                HITL 触发: %d 次
                """.formatted(
                total, completedTasks.get(), failedTasks.get(),
                total == 0 ? 0 : completedTasks.get() * 100.0 / total,
                calls, failedToolCalls.get(),
                calls == 0 ? 0 : failedToolCalls.get() * 100.0 / calls,
                hallucinatedToolCalls.get(),
                hitlCount.get());
    }
}
