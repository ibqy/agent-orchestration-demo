package com.xb.agent.controller;

import com.xb.agent.memory.EntityMemory;
import com.xb.agent.observe.AgentMetrics;
import com.xb.agent.tool.ToolRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Map;

/**
 * Agent 任务入口
 *
 * 心得: 编排是重 CPU/IO 任务，Controller 层包 Mono.fromCallable
 * 丢 boundedElastic 调度器，WebFlux 事件循环才不会被 LLM 阻塞调用拖死
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

    public AgentController(AgentTaskService taskService, ToolRegistry toolRegistry,
                           AgentMetrics metrics, EntityMemory entityMemory) {
        this.taskService = taskService;
        this.toolRegistry = toolRegistry;
        this.metrics = metrics;
        this.entityMemory = entityMemory;
    }

    /**
     * 提交 Agent 任务（异步执行，立即返回 taskId）
     * @param body 请求体，含 userId 和 goal
     * @return 包含 taskId 和执行结果的 Mono
     */
    @PostMapping("/tasks")
    public Mono<Map<String, String>> submit(@RequestBody Map<String, String> body) {
        String userId = body.getOrDefault("userId", "guest");
        String goal = body.getOrDefault("goal", "");
        if (goal.isBlank()) {
            return Mono.just(Map.of("error", "goal 不能为空"));
        }
        log.info("[API] 提交任务 user={} goal={}", userId, goal);

        return Mono.fromCallable(() -> taskService.submitTask(userId, goal))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> Map.of("taskId", extractId(result), "result", result));
    }

    /**
     * 查询任务快照（状态、步数、Token 用量等）
     * @param id 任务ID
     * @return 任务快照信息的 Mono
     */
    @GetMapping("/tasks/{id}")
    public Mono<Map<String, Object>> query(@PathVariable String id) {
        var snapshot = taskService.query(id);
        if (snapshot == null) {
            return Mono.just(Map.of("error", "任务不存在或已过期"));
        }
        return Mono.just(Map.of(
                "taskId", snapshot.getTaskId(),
                "state", snapshot.getState().toString(),
                "goal", snapshot.getUserGoal(),
                "tokenUsed", snapshot.getTokenUsed(),
                "steps", snapshot.getSteps().size()));
    }

    /**
     * 人工确认（HITL 恢复）: 将 WAIT_HUMAN 状态的任务恢复为 RUNNING
     * @param id 任务ID
     * @return 确认结果的 Mono
     */
    @PostMapping("/tasks/{id}/confirm")
    public Mono<Map<String, Object>> confirm(@PathVariable String id) {
        boolean ok = taskService.confirm(id);
        return Mono.just(Map.of("taskId", id, "confirmed", ok));
    }

    /**
     * 人工终止: 将 WAIT_HUMAN 状态的任务标记为 FAILED
     * @param id 任务ID
     * @return 终止结果的 Mono
     */
    @PostMapping("/tasks/{id}/abort")
    public Mono<Map<String, Object>> abort(@PathVariable String id) {
        boolean ok = taskService.abort(id);
        return Mono.just(Map.of("taskId", id, "aborted", ok));
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
     * 查询 Agent 运行指标（任务数、工具调用数、HITL 次数等）
     * @return 指标报告字符串的 Mono
     */
    @GetMapping("/metrics")
    public Mono<Map<String, String>> metricsReport() {
        return Mono.just(Map.of("report", metrics.report()));
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

    private String extractId(String result) {
        // "任务 xxx 完成/失败" 格式里抠出 taskId
        int start = result.indexOf("任务 ") + 3;
        if (start < 3) return "unknown";
        int end = result.indexOf(" ", start);
        return end > start ? result.substring(start, end) : result.substring(start);
    }
}
