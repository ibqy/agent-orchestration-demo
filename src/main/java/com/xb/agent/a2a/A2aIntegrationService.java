package com.xb.agent.a2a;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A2A（Agent-to-Agent）集成服务
 *
 * <p>封装 A2A 协议的客户端能力，让本 Agent 能发现并调用远程 Agent。
 * A2A 是 Google 2025 年提出的智能体间通信标准，
 * 不同框架（LangChain4j / Spring AI / AgentScope 等）构建的 Agent
 * 只要遵循 A2A 协议，就能互相协作。</p>
 *
 * <p>核心概念：</p>
 * <ul>
 *     <li><b>AgentCard</b>：Agent 的"名片"，包含名称、描述、技能列表、端点地址</li>
 *     <li><b>JSON-RPC 2.0</b>：A2A 使用 JSON-RPC 作为通信格式</li>
 * </ul>
 *
 * @author ibqy
 */
public class A2aIntegrationService {

    private static final Logger log = LoggerFactory.getLogger(A2aIntegrationService.class);

    private final String localAgentName;
    private final String localAgentDescription;
    private final List<String> localSkills;

    public A2aIntegrationService(String agentName, String description, List<String> skills) {
        this.localAgentName = agentName;
        this.localAgentDescription = description;
        this.localSkills = skills;
        log.info("A2A 服务已初始化: {} ({})", agentName, description);
    }

    /**
     * 获取本地 Agent 的 AgentCard 信息（名片）。
     * 其他 Agent 通过此信息发现本 Agent 的能力。
     */
    public Map<String, Object> getAgentCard() {
        Map<String, Object> card = new LinkedHashMap<>();
        card.put("name", localAgentName);
        card.put("description", localAgentDescription);
        card.put("skills", localSkills);
        card.put("protocol", "A2A (Agent-to-Agent)");
        card.put("version", "1.0");
        return card;
    }

    /**
     * 模拟调用远程 Agent（教学演示用）。
     * 生产环境中应通过 A2A SDK 的 JSON-RPC 客户端发送请求。
     *
     * @param remoteAgentUrl 远程 Agent 的端点地址
     * @param message        发送的消息
     * @return 远程 Agent 的响应
     */
    public String callRemoteAgent(String remoteAgentUrl, String message) {
        log.info("调用远程 Agent: {} 消息: {}", remoteAgentUrl, message);
        // 教学演示：模拟远程调用
        // 生产环境使用 A2A SDK:
        // A2aClient client = A2aClient.builder().url(remoteAgentUrl).build();
        // return client.sendMessage(message);
        return "[A2A 模拟响应] 远程 Agent (" + remoteAgentUrl + ") 收到消息: " + message;
    }

    /**
     * 返回 A2A 服务状态。
     */
    public Map<String, Object> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        status.put("initialized", true);
        status.put("protocol", "A2A (Agent-to-Agent)");
        status.put("agentCard", getAgentCard());
        status.put("hint", "A2A 客户端已就绪，可通过 /agent/a2a/call 调用远程 Agent");
        return status;
    }
}
