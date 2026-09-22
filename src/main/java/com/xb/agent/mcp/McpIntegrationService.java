package com.xb.agent.mcp;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.stdio.StdioMcpTransport;
import dev.langchain4j.service.tool.ToolProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP（Model Context Protocol）集成服务
 *
 * <p>封装 LangChain4j 的 MCP 客户端能力，支持通过 stdio 传输连接外部 MCP 服务器。
 * MCP 是 Anthropic 提出的 "AI 应用与外部工具之间的 USB-C 标准"，
 * 让 Agent 能动态发现并调用外部工具，而无需硬编码工具实现。</p>
 *
 * @author ibqy
 */
public class McpIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(McpIntegrationService.class);

    private final McpToolProvider toolProvider;
    private final boolean connected;

    /**
     * 创建 MCP 集成服务并尝试连接指定的 MCP 服务器。
     *
     * @param command 完整的 MCP 服务器启动命令
     *                （如 ["npx", "-y", "@modelcontextprotocol/server-filesystem", "."]）
     */
    public McpIntegrationService(List<String> command) {
        McpToolProvider provider = null;
        boolean ok = false;

        try {
            McpTransport transport = StdioMcpTransport.builder()
                    .command(new ArrayList<>(command))
                    .logEvents(true)
                    .build();

            McpClient client = DefaultMcpClient.builder()
                    .transport(transport)
                    .build();

            provider = McpToolProvider.builder()
                    .mcpClients(List.of(client))
                    .build();

            ok = true;
            log.info("MCP 客户端已初始化，命令: {}", command);
        } catch (Exception e) {
            log.warn("MCP 客户端初始化失败（不影响其他功能）: {}", e.getMessage());
        }

        this.toolProvider = provider;
        this.connected = ok;
    }

    /**
     * 获取 MCP 工具提供者，可挂载到 AiServices 的 toolProvider。
     */
    public ToolProvider getToolProvider() {
        return toolProvider;
    }

    /**
     * MCP 是否已成功连接。
     */
    public boolean isConnected() {
        return connected;
    }

    /**
     * 返回连接状态信息。
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("connected", connected);
        status.put("protocol", "MCP (Model Context Protocol)");
        status.put("transport", "stdio");
        if (!connected) {
            status.put("hint", "MCP 服务器未连接，请检查 Node.js 是否已安装");
        }
        return status;
    }
}
