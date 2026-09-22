package com.xb.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 复杂 Agent 编排 学习总结项目入口
 *
 * 主线：推理模式 → 多Agent架构 → 状态机持久化 → 分层记忆 → 工具体系 → 防护熔断
 *
 * @author ibqy
 */
@SpringBootApplication
@EnableScheduling
public class AgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }
}
