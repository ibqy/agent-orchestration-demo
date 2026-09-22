package com.xb.agent.guard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 防护模块自动配置 —— 激活 GuardProperties 配置绑定
 *
 * @author ibqy
 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardAutoConfiguration {
}
