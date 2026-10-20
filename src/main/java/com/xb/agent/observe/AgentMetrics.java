package com.xb.agent.observe;

import dev.langchain4j.model.output.TokenUsage;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 运行指标 —— 学习项目用内存计数，生产接 Micrometer
 *
 * 心得：评估 Agent 好坏看这几个:
 *   任务完成率 / 平均耗时 / 工具失败率 / 不必要调用次数 / HITL 触发率
 * "不必要调用"= Agent 幻觉式调用不存在的工具，这个指标最能反映 prompt 质量
 *
 * <p>延迟统计用固定容量环形缓冲 + 排序求分位数（P50/P95）。
 * 教学场景样本量小，不需要 Micrometer 的直方图；样本量上来后
 * 把 {@link #recordLlmCall} 换成 Micrometer Timer 即可，接口不用变。</p>
 *
 * @author ibqy
 */
@Component
public class AgentMetrics {

    private static final int LATENCY_SAMPLE_CAP = 512;

    private final AtomicLong totalTasks = new AtomicLong();
    private final AtomicLong completedTasks = new AtomicLong();
    private final AtomicLong failedTasks = new AtomicLong();
    private final AtomicLong toolCalls = new AtomicLong();
    private final AtomicLong failedToolCalls = new AtomicLong();
    private final AtomicLong hallucinatedToolCalls = new AtomicLong();  // 调用不存在的工具
    private final AtomicLong hitlCount = new AtomicLong();

    /** LLM 调用维度（由 UsageCapturingChatModel 记录） */
    private final AtomicLong llmCalls = new AtomicLong();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();

    /** 工具维度失败计数 */
    private final Map<String, AtomicLong> toolFailures = new ConcurrentHashMap<>();

    /** LLM 调用耗时样本（毫秒）—— 环形缓冲，多写少读场景无锁累加、读取时拷贝排序 */
    private final long[] latencySamples = new long[LATENCY_SAMPLE_CAP];
    private final AtomicLong latencyWriteIndex = new AtomicLong();
    private final AtomicLong latencyCount = new AtomicLong();

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

    /**
     * 记录一次 LLM 调用（耗时 + Token 用量）
     *
     * <p>由 {@link UsageCapturingChatModel} 在委派调用后触发。
     * TokenUsage 的字段在 LangChain4j 1.x 是 Integer（可为 null），
     * 部分模型/代理不返回用量时必须容错，不能 NPE。</p>
     *
     * @param durationNanos 调用耗时（纳秒）
     * @param usage         本次调用的 Token 用量（可为 null）
     */
    public void recordLlmCall(long durationNanos, TokenUsage usage) {
        llmCalls.incrementAndGet();
        recordLatency(durationNanos / 1_000_000);
        if (usage == null) return;
        if (usage.inputTokenCount() != null) promptTokens.addAndGet(usage.inputTokenCount());
        if (usage.outputTokenCount() != null) completionTokens.addAndGet(usage.outputTokenCount());
    }

    private void recordLatency(long millis) {
        long idx = latencyWriteIndex.getAndIncrement() % LATENCY_SAMPLE_CAP;
        latencySamples[(int) idx] = millis;
        latencyCount.incrementAndGet();
    }

    /** 延迟分位数（毫秒），无样本时返回 -1 */
    public long latencyP50() { return percentile(0.50); }
    public long latencyP95() { return percentile(0.95); }

    private long percentile(double p) {
        long count = Math.min(latencyCount.get(), LATENCY_SAMPLE_CAP);
        if (count == 0) return -1;
        long[] copy = Arrays.copyOf(latencySamples, (int) count);
        Arrays.sort(copy);
        int idx = (int) Math.min(count - 1, Math.ceil(p * count) - 1);
        return copy[Math.max(0, idx)];
    }

    /** 生成可读的指标报告文本
     * @return 格式化的指标汇总 */
    public String report() {
        long total = totalTasks.get();
        long calls = toolCalls.get();
        long llms = llmCalls.get();
        return """
                === Agent 指标 ===
                任务总数: %d / 完成: %d / 失败: %d（完成率 %.1f%%）
                工具调用: %d / 失败: %d（失败率 %.1f%%）/ 幻觉调用: %d
                HITL 触发: %d 次
                LLM 调用: %d 次（输入 %d / 输出 %d tokens）
                LLM 延迟: P50 %s ms / P95 %s ms
                """.formatted(
                total, completedTasks.get(), failedTasks.get(),
                total == 0 ? 0 : completedTasks.get() * 100.0 / total,
                calls, failedToolCalls.get(),
                calls == 0 ? 0 : failedToolCalls.get() * 100.0 / calls,
                hallucinatedToolCalls.get(),
                hitlCount.get(),
                llms, promptTokens.get(), completionTokens.get(),
                latencyP50() < 0 ? "N/A" : String.valueOf(latencyP50()),
                latencyP95() < 0 ? "N/A" : String.valueOf(latencyP95()));
    }

    // ==================== getter（供 JSON 端点/测试使用） ====================

    public long getTotalTasks() { return totalTasks.get(); }
    public long getCompletedTasks() { return completedTasks.get(); }
    public long getFailedTasks() { return failedTasks.get(); }
    public long getToolCalls() { return toolCalls.get(); }
    public long getFailedToolCalls() { return failedToolCalls.get(); }
    public long getHallucinatedToolCalls() { return hallucinatedToolCalls.get(); }
    public long getHitlCount() { return hitlCount.get(); }
    public long getLlmCalls() { return llmCalls.get(); }
    public long getPromptTokens() { return promptTokens.get(); }
    public long getCompletionTokens() { return completionTokens.get(); }
}
