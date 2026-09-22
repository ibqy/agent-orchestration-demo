package com.xb.agent.config;

import com.xb.agent.a2a.A2aIntegrationService;
import com.xb.agent.mcp.McpIntegrationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * MCP 与 A2A 集成配置
 *
 * <p>通过环境变量控制是否启用 MCP 客户端和 A2A 服务。
 * 默认关闭，不影响核心编排功能。</p>
 *
 * @author ibqy
 */
@Configuration
public class McpA2aConfig {

    /**
     * MCP 客户端：连接外部 MCP 工具服务器。
     * 设置 MCP_ENABLED=true 启用（需要 Node.js 环境）。
     */
    @Bean
    @ConditionalOnProperty(name = "mcp.enabled", havingValue = "true")
    public McpIntegrationService mcpIntegrationService() {
        return new McpIntegrationService(
                List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "."));
    }

    /**
     * A2A 服务：Agent 间通信能力。
     * 设置 A2A_ENABLED=true 启用。
     */
    @Bean
    @ConditionalOnProperty(name = "a2a.enabled", havingValue = "true")
    public A2aIntegrationService a2aIntegrationService() {
        return new A2aIntegrationService(
                "agent-orchestration-demo",
                "复杂 Agent 编排系统 —— 支持多推理模式、多 Agent 协作、状态机持久化",
                List.of("任务编排", "推理分析", "工具调用", "状态管理"));
    }
}
