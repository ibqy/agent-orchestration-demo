package com.xb.agent.observe;

import com.xb.agent.FakeChatModel;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 计量装饰器测试 —— 真实 Token 用量与延迟的捕获点
 *
 * 覆盖：正常记账、null 用量容错（LangChain4j 1.x 的字段是 Integer，可为 null）、
 * 异常路径也记延迟（超时排查的线索）、透传语义（装饰器不改变行为）。
 *
 * @author ibqy
 */
@DisplayName("UsageCapturingChatModel 计量装饰器")
class UsageCapturingChatModelTest {

    private AgentMetrics metrics;
    private UsageCapturingChatModel metered;

    @BeforeEach
    void setUp() {
        metrics = new AgentMetrics();
        metered = new UsageCapturingChatModel(new FakeChatModel(p -> "ok"), metrics);
    }

    @Test
    @DisplayName("正常调用：Token 用量与调用次数入账")
    void recordsUsageOnSuccess() {
        metered.chat("你好");

        assertThat(metrics.getLlmCalls()).isEqualTo(1);
        assertThat(metrics.getPromptTokens()).isEqualTo(10);   // FakeChatModel 固定返回 10/5
        assertThat(metrics.getCompletionTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("延迟分位：多次调用后 P50/P95 有值")
    void recordsLatencyPercentiles() {
        for (int i = 0; i < 10; i++) {
            metered.chat("第 " + i + " 次");
        }
        assertThat(metrics.getLlmCalls()).isEqualTo(10);
        assertThat(metrics.latencyP50()).isGreaterThanOrEqualTo(0);
        assertThat(metrics.latencyP95()).isGreaterThanOrEqualTo(metrics.latencyP50());
    }

    @Test
    @DisplayName("null 用量容错：模型不返回 usage 时不 NPE，调用仍计数")
    void nullUsageDoesNotCrash() {
        ChatModel noUsageModel = mock(ChatModel.class);
        when(noUsageModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("没有用量信息"))
                .build());
        var wrapped = new UsageCapturingChatModel(noUsageModel, metrics);

        String reply = wrapped.chat("你好");

        assertThat(reply).isEqualTo("没有用量信息");
        assertThat(metrics.getLlmCalls()).isEqualTo(1);
        assertThat(metrics.getPromptTokens()).isZero();
    }

    @Test
    @DisplayName("null 字段容错：TokenUsage 字段为 null 时按 0 计")
    void nullTokenCountsAreZero() {
        ChatModel partialModel = mock(ChatModel.class);
        when(partialModel.chat(any(ChatRequest.class))).thenReturn(ChatResponse.builder()
                .aiMessage(AiMessage.from("x"))
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(null, null))  // 字段为 null
                        .build())
                .build());
        var wrapped = new UsageCapturingChatModel(partialModel, metrics);

        wrapped.chat("你好");

        assertThat(metrics.getLlmCalls()).isEqualTo(1);
        assertThat(metrics.getPromptTokens()).isZero();
        assertThat(metrics.getCompletionTokens()).isZero();
    }

    @Test
    @DisplayName("异常透传：底层抛异常时原样抛出，且延迟仍入账")
    void exceptionPropagatesAndLatencyRecorded() {
        ChatModel failing = mock(ChatModel.class);
        when(failing.chat(any(ChatRequest.class)))
                .thenThrow(new RuntimeException("模型超时"));
        var wrapped = new UsageCapturingChatModel(failing, metrics);

        assertThatThrownBy(() -> wrapped.chat("你好"))
                .hasMessageContaining("模型超时");
        assertThat(metrics.getLlmCalls()).isEqualTo(1);   // 失败也记一次调用
        assertThat(metrics.latencyP50()).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("透传语义：装饰器不改变返回内容")
    void decoratorIsTransparent() {
        assertThat(metered.chat("你好")).isEqualTo("ok");
        assertThat(metered.unwrap()).isNotNull();
    }
}
