package com.xb.agent.config;

import com.xb.agent.observe.AgentMetrics;
import com.xb.agent.observe.UsageCapturingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 配置 —— 手动构建 ChatModel Bean
 *
 * 心得：没引 langchain4j-spring-boot-starter 就没有自动配置，
 * Planner/Worker/Reviewer/MemoryCompressor 全都注入 ChatModel，
 * 这一个 Bean 是全项目的供电站
 *
 * <p>计量装饰器挂在这里：真实模型（OpenAiChatModel）建好后包一层
 * {@link UsageCapturingChatModel}，所有 LLM 调用的 Token 用量和延迟
 * 自动进 {@link AgentMetrics}——业务代码零改动（详见装饰器类注释）。</p>
 *
 * @author ibqy
 */
@Configuration
public class LlmConfig {

    @Bean
    public ChatModel chatModel(
            AgentMetrics metrics,
            @Value("${langchain4j.open-ai.api-key}") String apiKey,
            @Value("${langchain4j.open-ai.base-url}") String baseUrl,
            @Value("${langchain4j.open-ai.model-name}") String modelName,
            @Value("${langchain4j.open-ai.temperature:0.2}") double temperature) {
        ChatModel raw = OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .temperature(temperature)
                .timeout(Duration.ofSeconds(60))
                .build();
        return new UsageCapturingChatModel(raw, metrics);
    }
}
