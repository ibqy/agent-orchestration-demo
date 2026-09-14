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

Java 21 + SpringBoot 3.4 + WebFlux + LangChain4j 1.0.0 + Spring Statemachine 4.0 + Redis + Milvus + Kafka + Resilience4j + OpenTelemetry

## 模块地图

| 模块 | 目录 | 解决什么问题 | 总结文档 |
|------|------|------------|---------|
| 推理模式 | [reasoning/](src/main/java/com/xb/agent/reasoning/) | ReAct 之外的 Plan-and-Solve / Self-Reflection | [01-推理模式](docs/01-推理模式总结.md) |
| 多Agent | [multi/](src/main/java/com/xb/agent/multi/) | Planner/Worker/Reviewer 角色拆分 | [02-多Agent架构](docs/02-多Agent架构总结.md) |
| 状态机 | [state/](src/main/java/com/xb/agent/state/) | FSM + 快照持久化 + 断点续跑 + HITL | [03-状态机与断点续跑](docs/03-状态机与断点续跑总结.md) |
| 分层记忆 | [memory/](src/main/java/com/xb/agent/memory/) | 短时/长时/实体三层记忆 | [04-分层记忆](docs/04-分层记忆总结.md) |
| 工具体系 | [tool/](src/main/java/com/xb/agent/tool/) | 注册中心/权限/熔断/回滚 | [05-工具体系](docs/05-工具体系总结.md) |
| 防护 | [guard/](src/main/java/com/xb/agent/guard/) | 死循环熔断/token预算/并发 | [06-防护与可观测](docs/06-防护与可观测总结.md) |
| 可观测 | [observe/](src/main/java/com/xb/agent/observe/) | OTel Span + 指标 | 同上 |
| 编排入口 | [controller/](src/main/java/com/xb/agent/controller/) | 全链路串联 + REST | [07-踩坑与面试](docs/07-踩坑与面试总结.md) |

## 全链路

```
POST /agent/tasks
  → 并发限制（Redis 计数）
  → 分层记忆构建上下文（实体 → 长时 → 短时）
  → FSM: INIT → PLANNING → RUNNING
  → Planner 拆解子任务
  → Worker 并行执行（虚拟线程）
      每次工具调用走防护管道:
      LoopBreaker → HITL高危检查 → Trace Span → 权限/熔断 → Token记账
  → Reviewer 评审（PASS/RETRY/ABORT）
  → FSM: REVIEW → SUCCESS
  → 记忆收尾（长时记忆抽取）
```

## 快速开始

```bash
docker compose up -d          # Redis + Milvus + Kafka
export OPENAI_API_KEY=sk-xxx
mvn spring-boot:run

# 提交任务
curl -X POST localhost:8081/agent/tasks \
  -H 'Content-Type: application/json' \
  -d '{"userId":"u1","goal":"查询订单A1001的金额并生成汇总"}'

# 查看指标
curl localhost:8081/agent/metrics
```

## 测试

不依赖外部服务的核心逻辑测试（解析容错/依赖调度/动态注册）：

```bash
mvn test -Dtest=AgentCoreTest
```

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
