<script setup>
import DefaultTheme from 'vitepress/theme'

const { Layout } = DefaultTheme
</script>

<template>
  <Layout>
    <template #home-features-before>
      <section class="sig">
        <div class="sig-card">
          <span class="sig-label">ORCHESTRATION · TOPOLOGY</span>
          <h3 class="sig-title">Planner 规划 · Worker 并行 · Reviewer 审查</h3>
          <div class="ao-tree">
            <span class="sg-node">长任务输入</span>
            <span class="ao-v">↓</span>
            <span class="sg-fill">Planner · 拆解与规划</span>
            <span class="ao-v ao-cap">并行调度 · 虚拟线程</span>
            <div class="ao-lane">
              <span class="sg-node">Worker ①</span>
              <span class="sg-node">Worker ②</span>
              <span class="sg-node">Worker ③</span>
            </div>
            <span class="ao-v">↓</span>
            <span class="sg-fill">Reviewer · 审查与自检</span>
            <span class="ao-v">↓</span>
            <span class="sg-line">产出 · FSM 状态持久化（可中断恢复）</span>
          </div>
          <div class="sg-chips">
            <span class="sg-chip">分层记忆</span>
            <span class="sg-chip">工具注册中心</span>
            <span class="sg-chip">死循环熔断</span>
            <span class="sg-chip">Token 预算</span>
            <span class="sg-chip">OTel 全链路追踪</span>
          </div>
          <p class="sig-note">单次 ToolCall 会跑飞，生产级 Agent 要的是可编排、可恢复、可自检——这套拓扑里的每个角色，都是仓库中一章的落地实现。</p>
        </div>
      </section>
    </template>
    <template #home-features-after>
      <div class="sig-more"><a href="https://ibqy.github.io/" target="_blank" rel="noopener">← ibqy.github.io · 探索全部项目</a></div>
    </template>
  </Layout>
</template>
