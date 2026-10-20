package com.xb.agent.observe;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 链路追踪 —— 任务为 Trace，子任务/工具调用为 Span
 *
 * 心得：日志里带上 traceId，出问题能一次捞全链路；
 * Span 粒度到"工具调用"级就够，再细存储成本爆炸
 *
 * @author ibqy
 */
@Component
public class AgentTraceLogger {

    private static final Logger log = LoggerFactory.getLogger(AgentTraceLogger.class);

    private final OpenTelemetry otel;
    private Tracer tracer;

    public AgentTraceLogger(OpenTelemetry otel) {
        this.otel = otel;
        // 构造器里直接取 Tracer：@PostConstruct 方案在脱离 Spring 容器的场景
        // （单元测试、手工装配）下 tracer 会是 null，一调用 spanBuilder 就 NPE。
        // 依赖字段的初始化不要交给生命周期回调
        this.tracer = otel.getTracer("agent-orchestration");
    }

    /** 工具调用 Span */
    public Span startToolSpan(String taskId, String toolName) {
        Span span = tracer.spanBuilder("tool:" + toolName)
                .setAttribute("agent.task_id", taskId)
                .setAttribute("agent.tool", toolName)
                .startSpan();
        log.debug("[Trace] span开始 task={} tool={}", taskId, toolName);
        return span;
    }

    /**
     * 正常结束 Span，记录 observation
     * @param span 当前 Span
     * @param observation 工具返回的观察结果
     */
    public void endSpan(Span span, String observation) {
        if (span == null) return;
        // observation 截断存储，全量走日志
        span.setAttribute("agent.observation",
                observation != null && observation.length() > 200
                        ? observation.substring(0, 200) : observation);
        span.end();
    }

    /**
     * 异常结束 Span，记录错误状态
     * @param span 当前 Span
     * @param e 导致失败的异常
     */
    public void endSpanError(Span span, Exception e) {
        if (span == null) return;
        span.setStatus(StatusCode.ERROR, e.getMessage());
        span.end();
    }

    /** 结构化步骤日志（回放用） */
    public void logStep(String taskId, String subTaskId, String thought,
                        String toolName, String observation) {
        log.info("[STEP] task={} sub={} tool={} thought={} obs={}",
                taskId, subTaskId, toolName,
                thought != null && thought.length() > 80 ? thought.substring(0, 80) : thought,
                observation != null && observation.length() > 200 ? observation.substring(0, 200) : observation);
    }
}
