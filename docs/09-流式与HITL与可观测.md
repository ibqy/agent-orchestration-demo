# 09 · 流式、HITL 与可观测性进阶

> 本篇对应 2026-10-16 改造批次：异步执行模型、SSE 流式过程、HITL 完整语义、
> 结构化并发批量执行、真实 Token 计量。每个主题都先讲"原来的坑"，再讲"改法"。

---

## 1. 异步执行模型：HITL 逼出来的架构改动

### 1.1 原来的形态

`POST /agent/tasks` 同步跑完整链路再返回结果。单 Agent 场景够用，
但只要引入"高危工具需人工审批"，这个模型就崩了：

- 审批等待几分钟，HTTP 请求就得挂几分钟
- 客户端超时断开，服务端的执行结果无处安放
- 想同时看多个任务进度？轮询接口返回的都是"正在跑"

### 1.2 改后形态

```
POST /agent/tasks ──► 登记 + 启动虚拟线程 ──► 立即返回 {taskId, status: SUBMITTED}
                                              │
虚拟线程跑全链路 ◄────────────────────────────┘
  ├─ 正常结束 → 结果落快照 + SSE RESULT 事件
  └─ 高危工具 → 任务线程 park 在 ApprovalGate
                 ├─ POST /confirm → 放行，继续执行
                 ├─ POST /abort   → 拒绝，任务收敛 FAILED
                 └─ 超时（默认 10min）→ 按拒绝处理
```

客户端两个观察通道：

| 通道 | 端点 | 适用 |
|---|---|---|
| 轮询 | `GET /agent/tasks/{id}` | 简单集成，秒级粒度 |
| SSE | `GET /agent/tasks/{id}/stream` | 实时看 Agent 思考过程，断线重连自动回放 |

**为什么用虚拟线程而不是响应式编排**：全链路是阻塞型 IO（LLM HTTP、Redis、工具调用），
用 WebFlux 硬写成非阻塞要把每个调用点包一层 `Mono`，代码可读性归零；
虚拟线程下同步代码直写，阻塞由 JVM 调度，一个任务一个线程成本极低。
WebFlux 只在 HTTP 边界保留（SSE 需要 `Flux`）。

---

## 2. SSE 流式过程：把 Agent 黑盒打开

### 2.1 事件从哪来

推理循环（ReAct / Plan-and-Solve）通过 `StepListener` 函数式接口抛事件，
不依赖任何传输实现——想换 WebSocket 不用动推理代码：

```java
listener.onStep("THOUGHT", "用户要求删除订单，调用删除工具");
listener.onStep("ACTION",   "deleteOrder {\"orderId\":\"A1001\"}");
listener.onStep("OBSERVATION", "订单 A1001 已删除");
```

编排层把它接到 `StepEventBus`（`Sinks.many().replay().limit(200)`）：

```java
StepListener listener = (type, msg) -> stepBus.publish(taskId, type, msg);
```

### 2.2 为什么选 replay().limit(200)

| 需求 | 方案 |
|---|---|
| 客户端断线重连不能丢上下文 | replay 回放历史事件 |
| 长任务不能把内存吃光 | limit(200) 上限 |
| 多端同时看同一任务 | many() 多订阅者 |
| 任务结束后迟到者能看到结果 | complete 后条目保留 30 分钟再清理 |

前端收到的事件序列（实测）：

```
event:TASK        data: 任务启动: 查询订单 A1001
event:PLAN        data: 任务拆解为 2 个子任务
event:THOUGHT     data: 需要先查订单状态
event:ACTION      data: orderQuery {"orderId":"A1001"}
event:OBSERVATION data: 已发货，金额 299 元
event:REVIEW      data: 子任务 1 评审通过
event:RESULT      data: 任务 xxx 执行结果: ...
```

### 2.3 一个反直觉点

**日志解决"开发者事后排查"，事件总线解决"用户实时看到 Agent 在干什么"**。
两者不是替代关系：日志仍然全量落盘，事件总线只推截断后的摘要
（`StepListener.abbreviate`，默认 300 字）——推送追求及时，不追求完整。

---

## 3. HITL 完整语义：从"翻状态"到"真暂停"

### 3.1 半成品长什么样

改之前，`confirm()` 的注释里留着 TODO：只把状态从 WAIT_HUMAN 翻回 RUNNING，
**不重新提交执行**——任务实际永远停在等待中。更隐蔽的是
`application.yml` 里一直配着 `agent.hitl.high-risk-tools`，
却没有 Properties 类绑定它，代码实际读的是 `risk == HIGH`：
**配置项"看起来生效"，其实是断的**。

### 3.2 完整语义三要素

```
① 登记   approvalGate.request(taskId, tool, args)   ← 必须在状态流转之前
② 流转   FSM: RUNNING → WAIT_HUMAN
③ 暂停   approvalGate.await(...)  ← 任务线程 park，虚拟线程不占 CPU
```

**时序顺序是刻意的**：先登记再流转。反过来（先流转后登记）的话，
审批请求可能在登记前到达，`confirm()` 找不到记录直接返回 false，
任务白等到超时——这类竞态在集成测试里才暴露得出来
（`ApprovalGateTest.confirmBeforeAwaitIsNotLost` 专门守它）。

### 3.3 拒绝为什么用异常而不是错误文本

被拒绝的调用如果只返回 `"[HITL] 审批被拒"` 这类 observation，
ReAct 循环会把它当普通工具结果继续推理，甚至换个参数重试同一个高危工具——
**人工关口就被绕过了**。抛 `HITLRejectedException` 穿透调用栈：

```
WorkerAgent(识别到，原样抛出) → BatchRunner(聚合进 outcomes) 
→ MultiAgentOrchestrator(从 errors() 里识别，向上抛)
→ AgentTaskService(收敛到 FAILED)
```

每一层都有理由不吞它，`AgentTaskServiceHITLTest.abortTerminatesTask`
验证的就是这条完整穿透链（没有它，abort 之后任务会把剩余子任务跑完并标 SUCCESS）。

### 3.4 超时是必须的

没有超时的人工通道 = 可以把任务无限期挂起的后门。
默认 10 分钟（`agent.hitl.approval-timeout-minutes`），超时按拒绝处理。

---

## 4. 结构化并发：一个比 join() 更隐蔽的坑

### 4.1 实测结论（重要）

`CompletableFuture.allOf()` **不会**因为某个子任务失败就提前完成——
JDK 用树形组合实现，它要等**所有**任务结束（无论成功失败）才抛异常。

实测：3 个任务（立即成功 / 睡 2 秒 / 抛异常），
`allOf().get()` 整整等了 2 秒才抛 `ExecutionException`。
所谓 "fail-fast" 名存实亡。

### 4.2 正确写法

给每个 future 挂 `whenComplete`，任一失败立即级联取消：

```java
futures.forEach(f -> f.whenComplete((r, ex) -> {
    if (ex != null) {
        futures.forEach(other -> { if (other != f) other.cancel(true); });
    }
}));
```

实测同样场景 4ms 收尾。`BatchRunner` 把三条语义打包：

| 语义 | 实现 |
|---|---|
| fail-fast | whenComplete 级联 cancel(true) |
| 整体超时 | `allOf().get(timeout)` + TimeoutException 取消 |
| 结果聚合 | Outcome 按批次下标对齐，含成功值与失败原因 |

### 4.3 为什么不用 StructuredTaskScope

它到 JDK 24 仍是预览特性，启用要 `--enable-preview`，
教学项目不该让编译器告警满天飞。三十行 CompletableFuture 语义完全一致。

### 4.4 诚实的局限

`cancel(true)` 只是发起中断。阻塞在 LLM HTTP 调用里的子任务
能否立即停下，取决于底层 IO 是否响应中断。生产上应传取消令牌
（CancellationToken）做真正的协作式取消。

---

## 5. 真实 Token 计量：装饰器选点

### 5.1 为什么包 ChatModel 而不是改业务代码

Planner/Worker/Reviewer/MemoryCompressor 四个调用点改一遍，
以后新增第五个还会漏。计量是横切关注点，挂 Bean 一次装配全局生效。

### 5.2 装饰点选 `chat(ChatRequest)`

LangChain4j 1.x 的 `ChatModel` 所有便捷方法（`chat(String)` 等）
最终都汇到 `chat(ChatRequest)`，包这一个方法全路径覆盖。
（用 `javap` 确认接口全是 default 方法、唯一抽象入口就是它。）

### 5.3 三个容错分支（都是实测踩出来的）

| 情况 | 处理 |
|---|---|
| `ChatResponseMetadata` 没调 `.usage()` | 字段为 null，判空跳过 |
| `TokenUsage` 字段为 null（1.x 起是 Integer） | 逐字段判空，按 0 计 |
| 调用抛异常 | 延迟仍入账（超时排查的线索），异常原样抛 |

`/agent/metrics` 现在返回真实数据（实测）：

```json
{"llmCalls": 1, "promptTokens": 107, "completionTokens": 16,
 "llmLatencyP50Ms": 268, "llmLatencyP95Ms": 268}
```

延迟分位用固定容量环形缓冲（512）+ 排序，样本量上来后换 Micrometer Timer，
`AgentMetrics` 接口不用动。

---

## 6. 执行轨迹落快照

`TaskSnapshot.addStep` 早期定义了却没有任何调用方，快照的 steps 永远是空列表——
"断点续跑"缺了最关键的一环数据。现在每次工具调用（含失败）都记 StepRecord：

```java
snapshot.addStep(new TaskSnapshot.StepRecord(
        taskId, null, toolName, argsJson, observation, System.currentTimeMillis()));
```

配合新增的 `result` / `reasoningMode` 字段，查询接口能回答：
任务用什么模式跑的、调了哪些工具、拿到了什么结果。

---

## 7. 测试策略：集成测试守链路，不守方法

这轮最重要的测试是 `AgentTaskServiceHITLTest`——它不测单个类的方法，
它测"人工介入"这条链路真的通了：

- Redis 用内存桩（`RedisTestFixtures`，Answer backing 到 Map/List）跑真实 Guard/快照逻辑
- ChatModel 用脚本化假模型（`FakeChatModel`，按 prompt 特征路由）驱动全流程
- 断言状态机流转 + 审批单内容 + 最终结果落盘

**教训**：升级前 35 个测试全绿，但 confirm() 只翻状态不恢复执行、
`agent.hitl.*` 配置没人绑定、`addStep` 没人调用——
单元测试只覆盖了"有测试的类"，链路断在没人测的地方。
链路级缺陷只能靠链路级测试发现。
