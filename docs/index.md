---
layout: home

hero:
  name: 复杂 Agent 编排实战
  text: 从单次 ToolCall 到生产级 Agent 系统
  tagline: 长任务、多角色、可中断恢复、可自检 —— 推理模式 → 多 Agent 架构 → 状态机持久化 → 分层记忆 → 工具体系 → 熔断防护，逐个落地生产级解法
  actions:
    - theme: brand
      text: 开始学习 →
      link: /01-推理模式总结
    - theme: alt
      text: GitHub 源码
      link: https://github.com/ibqy/agent-orchestration-demo

features:
  - icon: 🧠
    title: 推理模式
    details: ReAct 之外的 Plan-and-Solve / Self-Reflection 落地对比
  - icon: 👥
    title: 多 Agent 架构
    details: Planner / Worker / Reviewer 三角色拆分，虚拟线程并行调度
  - icon: ⏯️
    title: 状态机与断点续跑
    details: FSM + 快照持久化 + 人机协同（HITL），重启不丢任务
  - icon: 🧱
    title: 分层记忆
    details: 短时 / 长时 / 实体三层记忆，逐层构建任务上下文
  - icon: 🔧
    title: 工具体系
    details: 注册中心、权限控制、熔断、回滚，横切关注点全收口
  - icon: 🚨
    title: 防护与可观测
    details: 死循环熔断、Token 预算、OTel Span 全链路追踪与指标
---
