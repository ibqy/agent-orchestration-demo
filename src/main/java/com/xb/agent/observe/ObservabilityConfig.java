package com.xb.agent.observe;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.GlobalOpenTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OTel 配置 —— 学习项目用全局默认实例
 *
 * 心得: 生产接 OTel Collector + AutoConfigure，
 * 这里手动提供 bean 保证依赖注入能跑通
 */
@Configuration
public class ObservabilityConfig {

    @Bean
    public OpenTelemetry openTelemetry() {
        return GlobalOpenTelemetry.get();
    }
}
