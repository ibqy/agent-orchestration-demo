package com.xb.agent.guard;

import com.xb.agent.reasoning.ReasoningProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 防护模块自动配置 —— 激活 GuardProperties / HitlProperties / ReasoningProperties 配置绑定
 *
 * <p>心得：配置类要么全部注册要么全部漏注册——漏注册不会报错，
 * 只会让 application.yml 里的配置项"看起来生效实际没人读"。
 * （本项目早期 agent.hitl.* 就是漏注册的，详见 HitlProperties 注释。）</p>
 *
 * @author ibqy
 */
@Configuration
@EnableConfigurationProperties({GuardProperties.class, HitlProperties.class, ReasoningProperties.class})
public class GuardAutoConfiguration {
}
