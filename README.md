# 复杂 Agent 编排 —— 学习总结

> 从"单次 ToolCall + 内存对话"进阶到"长任务、多角色、可中断恢复、可自检"的 Agent 系统。
> 定位：个人学习总结，代码可跑、注释记录设计取舍，不是框架用法搬运。

## 为什么做这个项目

基础 Demo 的 Agent 三个致命问题都碰过：
1. 长任务跑一半服务重启，任务全丢
2. 单 Agent 全包，prompt 越写越长，错误互相污染
3. 死循环调工具，一晚上烧掉几百块 token

这个项目把解法逐个落地：**推理模式 → 多Agent → 状态机持久化 → 分层记忆 → 工具体系 → 熔断防护**。

## 技术栈

Java 21 + Spring Boot 3.5.16 + WebFlux + LangChain4j 1.22.0（langchain4j-mcp 1.22.0-beta32）+ Spring Statemachine 4.0.2 + Redis + Milvus + Kafka + Resilience4j 2.4.0 + OpenTelemetry 1.67.0 + MCP + A2A

## 模块地图

| 模块 | 目录 | 解决什么问题 | 总结文档 |
|------|------|------------|---------|
| 推理模式 | [reasoning/](src/main/java/com/xb/agent/reasoning/) | ReAct 之外的 Plan-and-Solve / Self-Reflection | [01-推理模式](docs/01-推理模式总结.md) |
| 多Agent | [multi/](src/main/java/com/xb/agent/multi/) | Planner/Worker/Reviewer 角色拆分 | [02-多Agent架构](docs/02-多Agent架构总结.md) |
| 状态机 | [state/](src/main/java/com/xb/agent/state/) | FSM + 快照持久化 + 断点续跑 + HITL 审批门 + SSE 事件总线 | [03-状态机与断点续跑](docs/03-状态机与断点续跑总结.md) |
| 分层记忆 | [memory/](src/main/java/com/xb/agent/memory/) | 短时/长时/实体三层记忆 | [04-分层记忆](docs/04-分层记忆总结.md) |
| 工具体系 | [tool/](src/main/java/com/xb/agent/tool/) | 注册中心/权限/熔断/回滚 | [05-工具体系](docs/05-工具体系总结.md) |
| 防护 | [guard/](src/main/java/com/xb/agent/guard/) | 死循环熔断/token预算/并发 | [06-防护与可观测](docs/06-防护与可观测总结.md) |
| 可观测 | [observe/](src/main/java/com/xb/agent/observe/) | OTel Span + 指标 | 同上 |
| 编排入口 | [controller/](src/main/java/com/xb/agent/controller/) | 全链路串联 + REST | [07-踩坑与面试](docs/07-踩坑与面试总结.md) |
| MCP 集成 | [mcp/](src/main/java/com/xb/agent/mcp/) | MCP 协议接入外部工具服务 | [08-MCP与A2A](docs/08-mcp-a2a-integration.md) |
| A2A 通信 | [a2a/](src/main/java/com/xb/agent/a2a/) | A2A 协议跨框架 Agent 协作 | 同上 |

## 全链路

```
POST /agent/tasks {"userId","goal","mode?"}
  → 并发限制（Redis 计数）
  → 创建快照 + FSM: INIT → PLANNING → RUNNING
  → 分层记忆构建上下文（实体 → 长时 → 短时）
  → 【异步】虚拟线程执行，HTTP 立即返回 taskId
  → Planner 拆解子任务
  → Worker 并行执行（BatchRunner：fail-fast + 超时 + 聚合）
      每次工具调用走防护管道:
      LoopBreaker → HITL审批 → Trace Span → 权限/熔断 → Token记账
                                     │
                                     ▼ 高危工具: 登记审批单 → FSM: WAIT_HUMAN
                                      → 任务线程 park 在 ApprovalGate
                                      → POST /confirm 放行 / POST /abort 拒绝
  → Reviewer 评审（PASS/RETRY/ABORT）
  → FSM: REVIEW → SUCCESS
  → 结果落快照 + SSE 终态事件（RESULT）
  → 记忆收尾（长时记忆抽取）

观察通道：
  GET  /agent/tasks/{id}          轮询状态与结果（含 mode/tokenUsed/steps）
  GET  /agent/tasks/{id}/stream   SSE 实时执行过程（THOUGHT/ACTION/OBSERVATION/HITL/RESULT）
  GET  /agent/approvals           待审批清单（HITL 工作台数据源）
```

## 快速开始

```bash
docker compose up -d          # Redis + Milvus + Kafka
export OPENAI_API_KEY=sk-xxx
mvn spring-boot:run

# 1) 提交任务（异步：立即返回 taskId，执行在虚拟线程上进行）
#    mode 可选：REACT（默认，开放问题）/ PLAN_AND_SOLVE（流程化）/ SELF_REFLECTION（准确性敏感）
curl -X POST localhost:8081/agent/tasks \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","goal":"查询订单A1001的金额并生成汇总","mode":"PLAN_AND_SOLVE"}'

# 2) 轮询状态与结果（任务完成前 result 字段不存在）
curl localhost:8081/agent/tasks/{taskId}

# 3) 或订阅 SSE 实时看 Agent 思考过程（断线重连自动回放最近 200 条）
curl -N localhost:8081/agent/tasks/{taskId}/stream

# 4) 高危工具触发审批时：查待审批清单 → 放行或拒绝
curl localhost:8081/agent/approvals
curl -X POST localhost:8081/agent/tasks/{taskId}/confirm
curl -X POST localhost:8081/agent/tasks/{taskId}/abort

# 5) 指标（含真实 Token 用量与 LLM 延迟分位）
curl localhost:8081/agent/metrics
```

> Windows 注意：系统自带 curl.exe 对本地 POST 偶发 "Failed to read HTTP message"，
> 用 `Invoke-RestMethod` 或 WSL  curl 替代即可（已实测）。

## 测试

不依赖外部服务的核心逻辑测试（65 个测试，Guard/Memory/Tool/Parser 用内存 Redis 桩跑真实逻辑）：

```bash
mvn test
```

测试覆盖：
- **ActionParser**: 容错解析（工具调用/最终答案/格式错误）
- **TaskPlan**: 依赖调度（子任务拓扑排序）
- **ToolRegistry**: 动态注册/下线
- **Guard**: 并发限制、循环熔断、Token 预算
- **Memory**: 实体记忆用户隔离、记忆压缩阈值
- **ToolExecutor**: 权限校验、错误分类反馈
- **GlobalExceptionHandler**: 统一响应封装、ResponseStatusException/参数异常/未知异常处理
- **ReasoningAgentFactory**（新增）: 三种模式真的被组装上线 + 模式回退 + 步骤事件
- **ApprovalGate**（新增）: 批准/拒绝/超时/竞态时序（先登记后流转不丢审批）
- **StepEventBus**（新增）: 实时推送 + 迟到订阅回放 + 幂等 complete
- **BatchRunner**（新增）: fail-fast 级联取消 + 整体超时 + 结果聚合 + 控制流异常识别
- **UsageCapturingChatModel**（新增）: Token 记账/null 容错/异常透传
- **AgentTaskService HITL 全流程**（新增）: 提交→审批等待→批准→恢复→出结果 / 拒绝→FAILED / 模式入链

## 实现边界

**已实现（可运行）：**
- ✅ 三种推理模式（ReAct / Plan-and-Solve / Self-Reflection，工厂组装 + 运行时按请求切换）
- ✅ 多 Agent 编排（Planner/Worker/Reviewer 三角色）
- ✅ 状态机 + 快照持久化（Redis 存储，支持断点续跑）
- ✅ 分层记忆（短时/长时/实体三层）
- ✅ 工具注册中心 + 权限校验 + 熔断降级
- ✅ 防护机制（并发限制/循环熔断/Token 预算）
- ✅ HITL 完整语义（审批单登记 → 任务线程 park → confirm/abort 唤醒 → 超时收敛；高危清单走配置 `agent.hitl.high-risk-tools`）
- ✅ 异步执行模型（虚拟线程 + 立即返回 taskId；轮询/SSE 双观察通道）
- ✅ SSE 流式执行过程（replay 回放，支持断线重连）
- ✅ 结构化并发批量执行（fail-fast + 超时 + 聚合，附 `allOf` 不 fail-fast 的实测结论）
- ✅ 真实 Token 计量（UsageCapturingChatModel 装饰器 + 延迟 P50/P95）
- ✅ 执行轨迹落快照（每次工具调用记 StepRecord）
- ✅ OpenTelemetry 可观测（Span + Metrics）
- ✅ REST API + 全链路串联
- ✅ MCP 协议集成（LangChain4j MCP 客户端，stdio 传输）
- ✅ A2A 智能体通信（AgentCard 发现 + 远程调用）
- ✅ 65 个单元/集成测试

**教学简化（生产需补充）：**
- ⚠️ 工具示例为模拟数据（DemoTools），生产需对接真实 API
- ⚠️ 权限硬编码在 DemoTools，生产需对接权限系统（如 Spring Security）
- ⚠️ LLM 使用 OpenAI 兼容接口，生产可按需切换（配置 `llm.provider`）
- ⚠️ Milvus/Kafka 仅配置未使用，生产用于向量检索/事件驱动
- ⚠️ 取消语义靠线程中断，生产应换取消令牌（见 BatchRunner 注释）

**未实现（学习路线扩展）：**
- ❌ 分布式锁（多 Pod 部署时 HITL 审批需分布式锁）
- ❌ 工具版本管理（工具签名变更时旧任务回放会失败）
- ❌ 记忆压缩的评估指标（压缩后信息损失度量）
- ❌ 前端可视化（任务进度/审批界面）
- ❌ Kafka 任务总线（task 包仍是未接线模块）

## 提交历史（学习路线）

1. 脚手架（技术栈选型）
2. 推理模式（ReAct / Plan-and-Solve / Self-Reflection）
3. 多 Agent 架构（三角色 + 并行调度）
4. 状态机 + 快照（断点续跑是本项目核心难点）
5. 分层记忆（三层职责划分）
6. 工具体系（横切关注点全部收口）
7. 防护熔断 + 可观测
8. REST 入口 + 全链路编排 + 测试
9. 学习总结文档
10. 全局异常处理 + 统一响应封装（ApiResponse + GlobalExceptionHandler）
