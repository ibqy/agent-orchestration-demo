package com.xb.agent.guard;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 激活 GuardProperties 绑定 */
@Configuration
@EnableConfigurationProperties(GuardProperties.class)
public class GuardAutoConfiguration {
}
