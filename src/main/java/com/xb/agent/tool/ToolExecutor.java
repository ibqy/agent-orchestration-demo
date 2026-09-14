package com.xb.agent.tool;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具执行器 —— 权限校验 + 熔断 + 错误分类反馈
 *
 * 心得1: 网络类错误重试，业务类错误不重试直接喂回 LLM
 * 心得2: 错误反馈必须是结构化文本，让 Agent 自己决策
 *   重试 / 改参数 / 终止 —— 这比硬编码错误处理灵活得多
 */
@Component
public class ToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(ToolExecutor.class);

    private final ToolRegistry registry;
    private final ToolPermission permission;

    public ToolExecutor(ToolRegistry registry, ToolPermission permission) {
        this.registry = registry;
        this.permission = permission;
    }

    /**
     * 执行工具（Agent 统一入口）
     * @return 观察结果（含错误反馈）
     */
    @CircuitBreaker(name = "toolCircuitBreaker", fallbackMethod = "degraded")
    public String execute(String userId, String toolName, String argsJson) {
        AgentTool tool = registry.get(toolName);
        if (tool == null) {
            return "[ERROR] 工具不存在: " + toolName + "，可用: " +
                    registry.listAll().stream().map(ToolDescriptor::getName).toList();
        }

        ToolDescriptor desc = tool.descriptor();
        if (!permission.check(userId, desc)) {
            log.warn("[Executor] 越权拦截 user={} tool={}", userId, toolName);
            return "[ERROR] 无权限调用 " + toolName + "（需要权限: " + desc.getPermission() + "）";
        }

        try {
            String result = tool.execute(argsJson);
            log.info("[Executor] {} 执行成功", toolName);
            return result;
        } catch (IllegalArgumentException e) {
            return "[ERROR] 参数错误: " + e.getMessage() + "。建议修改入参后重试";
        } catch (Exception e) {
            log.error("[Executor] {} 执行异常", toolName, e);
            return "[ERROR] 工具执行失败: " + e.getMessage() + "。如为网络问题可重试，否则建议换方案";
        }
    }

    /** 熔断降级: 返回结构化提示，让 Agent 切换备选工具 */
    @SuppressWarnings("unused")
    private String degraded(String userId, String toolName, String argsJson, Throwable t) {
        log.warn("[Executor] 熔断触发 tool={} reason={}", toolName, t.getMessage());
        return "[DEGRADED] 工具 " + toolName + " 当前不可用（已熔断）。请改用其他工具或稍后重试";
    }
}
