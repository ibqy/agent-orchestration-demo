package com.xb.agent.controller;

import com.xb.agent.a2a.A2aIntegrationService;
import com.xb.agent.memory.EntityMemory;
import com.xb.agent.mcp.McpIntegrationService;
import com.xb.agent.observe.AgentMetrics;
import com.xb.agent.reasoning.ReasoningMode;
import com.xb.agent.state.StepEventBus;
import com.xb.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Agent 任务入口
 *
 * 心得: 编排是重 IO 任务，WebFlux 事件循环不能被 LLM 阻塞调用拖死。
 * 本期改造后 submit 只做"登记 + 启动虚拟线程"立即返回，
 * 执行过程通过轮询 /agent/tasks/{id} 或订阅 /agent/tasks/{id}/stream 观察——
 * 这正是 HITL（审批等待）对 API 形态的要求：请求线程不能陪着任务一起 park。
 *
 * @author ibqy
 */
@RestController
@RequestMapping("/agent")
public class AgentController {

    private static final Logger log = LoggerFactory.getLogger(AgentController.class);

    private final AgentTaskService taskService;
    private final ToolRegistry toolRegistry;
    private final AgentMetrics metrics;
    private final EntityMemory entityMemory;
    private final StepEventBus stepBus;
    private final McpIntegrationService mcpService;
    private final A2aIntegrationService a2aService;

    public AgentController(AgentTaskService taskService, ToolRegistry toolRegistry,
                           AgentMetrics metrics, EntityMemory entityMemory,
                           StepEventBus stepBus,
                           @Autowired(required = false) McpIntegrationService mcpService,
                           @Autowired(required = false) A2aIntegrationService a2aService) {
        this.taskService = taskService;
        this.toolRegistry = toolRegistry;
        this.metrics = metrics;
        this.entityMemory = entityMemory;
        this.stepBus = stepBus;
        this.mcpService = mcpService;
        this.a2aService = a2aService;
    }

    /**
     * 提交 Agent 任务（异步执行，立即返回 taskId）
     *
     * <p>请求体可选字段：userId、goal、mode（REACT / PLAN_AND_SOLVE / SELF_REFLECTION，
     * 缺省用配置 agent.reasoning.mode；非法值回退默认并在响应里提示）。</p>
     *
     * @param body 请求体，含 userId、goal，可选 mode
     * @return {taskId, status, mode, hint}
     */
    @PostMapping("/tasks")
    public Mono<Map<String, Object>> submit(@RequestBody Map<String, String> body) {
        String userId = body.getOrDefault("userId", "guest");
        String goal = body.getOrDefault("goal", "");
        if (goal.isBlank()) {
            return Mono.just(Map.of("error", "goal 不能为空"));
        }

        // 推理模式解析：容错优先——非法 mode 不该让整笔请求失败
        String modeParam = body.get("mode");
        ReasoningMode mode;
        boolean modeFallback = false;
        try {
            mode = modeParam == null || modeParam.isBlank()
                    ? null  // null = 用配置默认值
                    : ReasoningMode.valueOf(modeParam.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            mode = null;
            modeFallback = true;
            log.warn("[API] 非法推理模式 mode={}，回退默认", modeParam);
        }

        String taskId = taskService.submitTask(userId, goal, mode);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", taskId);
        resp.put("status", "SUBMITTED");
        resp.put("userId", userId);
        if (mode != null) resp.put("mode", mode.name());
        if (modeFallback) resp.put("warning", "mode 无效，已回退默认模式: " + modeParam);
        resp.put("hint", "轮询 GET /agent/tasks/" + taskId + " 查看状态与结果；" +
                "订阅 GET /agent/tasks/" + taskId + "/stream 实时查看执行过程");
        return Mono.just(resp);
    }

    /**
     * 查询任务快照（状态、步数、Token 用量、最终结果）
     * @param id 任务ID
     * @return 任务快照信息的 Mono
     */
    @GetMapping("/tasks/{id}")
    public Mono<Map<String, Object>> query(@PathVariable String id) {
        var snapshot = taskService.query(id);
        if (snapshot == null) {
            return Mono.just(Map.of("error", "任务不存在或已过期"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("taskId", snapshot.getTaskId());
        resp.put("state", snapshot.getState().toString());
        resp.put("goal", snapshot.getUserGoal());
        resp.put("tokenUsed", snapshot.getTokenUsed());
        resp.put("steps", snapshot.getSteps().size());
        if (snapshot.getReasoningMode() != null) {
            resp.put("mode", snapshot.getReasoningMode().name());
        }
        if (snapshot.getResult() != null) {
            resp.put("result", snapshot.getResult());
        }
        return Mono.just(resp);
    }

    /**
     * 实时执行过程（SSE）—— Agent 思考过程的透视窗
     *
     * <p>事件类型：TASK / PLAN / THOUGHT / ACTION / OBSERVATION / REVIEW /
     * HITL / BATCH / RESULT / ERROR。断线重连会自动回放最近 200 条。</p>
     *
     * @param id 任务ID
     * @return SSE 事件流
     */
    @GetMapping(value = "/tasks/{id}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<Map<String, Object>>> stream(@PathVariable String id) {
        return stepBus.subscribe(id).map(event -> ServerSentEvent.<Map<String, Object>>builder()
                .id(id + "-" + event.timestamp())
                .event(event.type())
                .data(Map.of(
                        "type", event.type(),
                        "message", event.message(),
                        "timestamp", event.timestamp()))
                .build());
    }

    /**
     * 人工确认（HITL 恢复）: 唤醒等待审批的任务线程，被 park 的执行继续
     * @param id 任务ID
     * @return {taskId, confirmed}
     */
    @PostMapping("/tasks/{id}/confirm")
    public Mono<Map<String, Object>> confirm(@PathVariable String id) {
        boolean ok = taskService.confirm(id);
        return Mono.just(Map.of("taskId", id, "confirmed", ok,
                "hint", ok ? "已批准，任务继续执行" : "没有等待中的审批（可能已超时或被处理）"));
    }

    /**
     * 人工终止: 以"拒绝"唤醒等待中的任务线程，任务收敛到 FAILED
     * @param id 任务ID
     * @return {taskId, aborted}
     */
    @PostMapping("/tasks/{id}/abort")
    public Mono<Map<String, Object>> abort(@PathVariable String id) {
        boolean ok = taskService.abort(id);
        return Mono.just(Map.of("taskId", id, "aborted", ok,
                "hint", ok ? "已拒绝，任务将终止" : "没有等待中的审批"));
    }

    /**
     * 待审批清单（HITL 工作台的数据源）
     */
    @GetMapping("/approvals")
    public Mono<Map<String, Object>> approvals() {
        var pending = taskService.pendingApprovals().stream()
                .map(p -> Map.<String, Object>of(
                        "taskId", p.taskId(),
                        "toolName", p.toolName(),
                        "argsJson", p.argsJson() == null ? "" : p.argsJson(),
                        "requestedAt", p.requestedAt()))
                .toList();
        return Mono.just(Map.of("count", pending.size(), "pending", pending));
    }

    /**
     * 查询已注册工具清单（供前端展示/调试）
     * @return 工具列表及数量的 Mono
     */
    @GetMapping("/tools")
    public Mono<Map<String, Object>> tools() {
        return Mono.just(Map.of(
                "count", toolRegistry.listAll().size(),
                "tools", toolRegistry.listAll().stream()
                        .map(t -> Map.of(
                                "name", t.getName(),
                                "desc", t.getDescription(),
                                "risk", t.getRisk().toString(),
                                "permission", t.getPermission()))
                        .toList()));
    }

    /**
     * 查询 Agent 运行指标（任务数、工具调用数、HITL 次数、LLM 用量与延迟分位等）
     * @return 指标报告（文本 + 结构化字段）的 Mono
     */
    @GetMapping("/metrics")
    public Mono<Map<String, Object>> metricsReport() {
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("report", metrics.report());
        resp.put("totalTasks", metrics.getTotalTasks());
        resp.put("completedTasks", metrics.getCompletedTasks());
        resp.put("failedTasks", metrics.getFailedTasks());
        resp.put("toolCalls", metrics.getToolCalls());
        resp.put("failedToolCalls", metrics.getFailedToolCalls());
        resp.put("hallucinatedToolCalls", metrics.getHallucinatedToolCalls());
        resp.put("hitlCount", metrics.getHitlCount());
        resp.put("llmCalls", metrics.getLlmCalls());
        resp.put("promptTokens", metrics.getPromptTokens());
        resp.put("completionTokens", metrics.getCompletionTokens());
        resp.put("llmLatencyP50Ms", metrics.latencyP50());
        resp.put("llmLatencyP95Ms", metrics.latencyP95());
        return Mono.just(resp);
    }

    /**
     * 演示: 动态下线工具（模拟生产环境工具熔断/下线场景）
     * @param name 工具名称
     * @return 下线结果的 Mono
     */
    @PostMapping("/tools/{name}/offline")
    public Mono<Map<String, Object>> offline(@PathVariable String name) {
        toolRegistry.unregister(name);
        return Mono.just(Map.of("tool", name, "status", "offline"));
    }

    /**
     * 演示: 写入实体记忆（如用户偏好、订单ID等长期记忆）
     * @param body 请求体，含 userId、type、value
     * @return 保存结果的 Mono
     */
    @PostMapping("/memory/entity")
    public Mono<Map<String, Object>> saveEntity(@RequestBody Map<String, String> body) {
        entityMemory.save(body.getOrDefault("userId", "guest"),
                body.getOrDefault("type", "orderId"),
                body.getOrDefault("value", ""));
        return Mono.just(Map.of("status", "saved"));
    }

    // ==================== MCP / A2A 端点 ====================

    /**
     * GET /agent/mcp/status —— MCP 连接状态
     */
    @GetMapping("/mcp/status")
    public Mono<Map<String, Object>> mcpStatus() {
        if (mcpService != null) {
            return Mono.just(mcpService.getStatus());
        }
        return Mono.just(Map.of("connected", false, "hint", "MCP 未启用（MCP_ENABLED=false）"));
    }

    /**
     * GET /agent/a2a/status —— A2A 服务状态
     */
    @GetMapping("/a2a/status")
    public Mono<Map<String, Object>> a2aStatus() {
        if (a2aService != null) {
            return Mono.just(a2aService.getStatus());
        }
        return Mono.just(Map.of("initialized", false, "hint", "A2A 未启用（A2A_ENABLED=false）"));
    }

    /**
     * GET /agent/a2a/card —— 本地 Agent 的 A2A 名片
     */
    @GetMapping("/a2a/card")
    public Mono<Map<String, Object>> a2aCard() {
        if (a2aService != null) {
            return Mono.just(a2aService.getAgentCard());
        }
        return Mono.just(Map.of("error", "A2A 未启用"));
    }

    /**
     * GET /agent/a2a/call?remoteUrl=...&message=... —— 调用远程 Agent（模拟）
     */
    @GetMapping("/a2a/call")
    public Mono<Map<String, Object>> a2aCall(
            @RequestParam(defaultValue = "http://localhost:8082/a2a/") String remoteUrl,
            @RequestParam(defaultValue = "你好，请介绍你的能力") String message) {
        if (a2aService != null) {
            String response = a2aService.callRemoteAgent(remoteUrl, message);
            return Mono.just(Map.of("remoteAgent", remoteUrl, "message", message, "response", response));
        }
        return Mono.just(Map.of("error", "A2A 未启用（A2A_ENABLED=false）"));
    }
}
