# 08 · MCP 与 A2A 集成

> 前置知识：[05-工具体系](/05-工具体系总结) | 涉及模块：mcp/、a2a/
> 学习目标：让 Agent 系统接入 MCP 外部工具和 A2A 跨框架协作

## 1. MCP：让 Agent 调用外部工具

> **MCP（Model Context Protocol）** 是 Anthropic 提出的 "AI 应用与外部工具之间的 USB-C 标准"。

### 1.1 集成架构

```
┌──────────────────┐    LangChain4j    ┌──────────────┐
│ Agent 编排系统    │ ←──────────────→ │ MCP Server   │
│ (LangChain4j)    │   McpToolProvider│ (文件系统…)   │
└──────────────────┘                   └──────────────┘
```

### 1.2 依赖

```xml
<dependency>
    <groupId>dev.langchain4j</groupId>
    <artifactId>langchain4j-mcp</artifactId>
    <version>1.0.0-beta1</version>
</dependency>
```

### 1.3 核心代码

```java
// 1) 创建 stdio 传输
McpTransport transport = StdioMcpTransport.builder()
    .command(List.of("npx", "-y", "@modelcontextprotocol/server-filesystem", "."))
    .build();

// 2) 创建 MCP 客户端
McpClient client = DefaultMcpClient.builder()
    .transport(transport)
    .build();

// 3) 桥接为 LangChain4j ToolProvider
McpToolProvider provider = McpToolProvider.builder()
    .mcpClients(List.of(client))
    .build();

// 4) 挂载到 AiServices
Bot bot = AiServices.builder(Bot.class)
    .chatModel(model)
    .toolProvider(provider)
    .build();
```

### 1.4 运行

```bash
export MCP_ENABLED=true
curl localhost:8081/agent/mcp/status
```

## 2. A2A：让 Agent 互相协作

> **A2A（Agent-to-Agent）** 是 Google 2025 年提出的智能体间通信标准。

### 2.1 MCP vs A2A

| 维度 | MCP | A2A |
|------|-----|-----|
| 解决什么 | AI ↔ 工具 | Agent ↔ Agent |
| 核心概念 | Tool / Server | AgentCard / JSON-RPC |
| 本项目 | McpIntegrationService | A2aIntegrationService |

### 2.2 核心代码

```java
// AgentCard —— Agent 的名片
Map<String, Object> card = a2aService.getAgentCard();
// → {name, description, skills, protocol, version}

// 调用远程 Agent
String response = a2aService.callRemoteAgent(remoteUrl, message);
```

### 2.3 运行

```bash
export A2A_ENABLED=true
curl localhost:8081/agent/a2a/card    # 查看 AgentCard
curl localhost:8081/agent/a2a/status  # 查看状态
```

## 3. 小结

- **MCP** 让 LangChain4j Agent 动态接入外部工具（USB-C 接口）
- **A2A** 让不同框架的 Agent 互相发现和调用（外交通道）
- 二者互补：**MCP + A2A = Agent 的完整连接能力**
- 本项目通过 `@ConditionalOnProperty` 控制启用，默认不影响核心编排功能
