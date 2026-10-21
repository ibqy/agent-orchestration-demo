import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'zh-CN',
  title: '复杂 Agent 编排实战',
  description: '生产级 Agent 系统教学：推理模式、多 Agent 架构、状态机持久化、分层记忆、工具体系、熔断防护、MCP、A2A',
  base: '/agent-orchestration-demo/',
  lastUpdated: true,
  markdown: {
    config(md) {
      const defaultLink =
        md.renderer.rules.link_open ||
        ((tokens, idx, options, _env, self) => self.renderToken(tokens, idx, options))
      md.renderer.rules.link_open = (tokens, idx, options, env, self) => {
        const href = tokens[idx].attrGet('href')
        if (href && href.startsWith('../')) {
          const rel = href.replace(/^(\.\.\/)+/, '')
          const kind = /\.[A-Za-z]+$/.test(rel) ? 'blob' : 'tree'
          tokens[idx].attrSet('href', `https://github.com/ibqy/agent-orchestration-demo/${kind}/main/${rel}`)
        }
        return defaultLink(tokens, idx, options, env, self)
      }
    }
  },
  themeConfig: {
    nav: [
      { text: '首页', link: '/' },
      { text: '学习路径', link: 'https://ibqy.github.io/curriculum' },
      { text: 'GitHub', link: 'https://github.com/ibqy/agent-orchestration-demo' }
    ],
    sidebar: [
      {
        text: '学习总结',
        items: [
          { text: '01 · 推理模式', link: '/01-推理模式总结' },
          { text: '02 · 多 Agent 架构', link: '/02-多Agent架构总结' },
          { text: '03 · 状态机与断点续跑', link: '/03-状态机与断点续跑总结' },
          { text: '04 · 分层记忆', link: '/04-分层记忆总结' },
          { text: '05 · 工具体系', link: '/05-工具体系总结' },
          { text: '06 · 防护与可观测', link: '/06-防护与可观测总结' },
          { text: '07 · 踩坑与面试', link: '/07-踩坑与面试总结' },
          { text: '08 · MCP 与 A2A', link: '/08-mcp-a2a-integration' },
          { text: '09 · 流式、HITL 与可观测', link: '/09-流式与HITL与可观测' }
        ]
      }
    ],
    socialLinks: [
      { icon: 'github', link: 'https://github.com/ibqy/agent-orchestration-demo' }
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3], label: '本页目录' },
    docFooter: { prev: '上一篇', next: '下一篇' },
    lastUpdated: { text: '最后更新于' },
    darkModeSwitchLabel: '外观',
    lightModeSwitchTitle: '切换到浅色模式',
    darkModeSwitchTitle: '切换到深色模式',
    sidebarMenuLabel: '文档',
    returnToTopLabel: '回到顶部',
    footer: {
      message: '个人学习总结 · 代码可跑 · 注释记录设计取舍',
      copyright: 'Copyright © 2026 ibqy'
    }
  }
})
