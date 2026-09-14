# 多 Agent 架构 —— 学习总结

> 结论先行：多 Agent 的价值是**角色分工后各自 prompt 短小聚焦**，不是"Agent 越多越强"。

## 三角色职责

| 角色 | 输入 | 输出 | 代码 |
|------|------|------|------|
| Planner | 用户需求 | 带依赖编号的子任务清单 | [PlannerAgent](../src/main/java/com/xb/agent/multi/PlannerAgent.java) |
| Worker | 单个子任务 | 执行结果 | [WorkerAgent](../src/main/java/com/xb/agent/multi/WorkerAgent.java) |
| Reviewer | 目标+结果 | PASS/RETRY/ABORT | [ReviewerAgent](../src/main/java/com/xb/agent/multi/ReviewerAgent.java) |

## 关键设计取舍

### 1. Planner 输出行式格式而不是 JSON

让 LLM 输出 `编号 | 目标 | 依赖` 行式清单，比要求 JSON 稳得多——LLM 生成竖线分隔是概率最高的形态。解析容错在 [PlannerAgent.createPlan](../src/main/java/com/xb/agent/multi/PlannerAgent.java) 里只有 20 行。

### 2. Worker 复用 ReActAgent，不另写循环

[WorkerAgent](../src/main/java/com/xb/agent/multi/WorkerAgent.java) 里直接 `new ReActAgent(model, toolInvoker, 5)`。多 Agent 换的是**分工**，不是每个角色重新发明推理逻辑。

### 3. Reviewer 结论必须结构化

PASS/RETRY/ABORT 三选一。让 Reviewer 输出一大段评审意见再靠字符串匹配解读，是维护噩梦。

### 4. 并行调度按"依赖批次"

[MultiAgentOrchestrator](../src/main/java/com/xb/agent/multi/MultiAgentOrchestrator.java) 的调度循环：

```java
while (!plan.allFinished()) {
    List<SubTask> batch = plan.runnableTasks();   // 依赖满足的子任务
    if (batch.isEmpty()) break;                    // 依赖成环或前置全失败
    // 虚拟线程并行执行本批
}
```

[TaskPlan.runnableTasks()](../src/main/java/com/xb/agent/multi/TaskPlan.java) 做拓扑过滤，批内并行用 `Executors.newVirtualThreadPerTaskExecutor()`——每个阻塞型 LLM 调用一个虚拟线程，成本可忽略。

### 5. 评审重试上限 2 次

没有上限时遇到过：Reviewer 说结果可疑 → Worker 重跑 → Reviewer 还是说可疑 → 无限拉扯。重试上限是**所有 Agent 交互的必备保险丝**。

## 踩坑：依赖成环

Planner 偶尔会生成 `2 依赖 3, 3 依赖 2` 的循环依赖。`runnableTasks()` 返回空 + `allFinished()` 为 false 的组合就是死锁信号，直接终止并报告未完成的子任务，比卡死强。

## 什么时候不要多 Agent

- 任务只有 1-2 步：单 ReAct 更快更省
- 子任务间强依赖串行：Planner 拆了也只是串行跑，白多两轮 LLM 调用
- 需要极低延迟：每层 Agent 交互都是一次 LLM 往返
