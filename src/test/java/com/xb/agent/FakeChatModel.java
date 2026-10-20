package com.xb.agent;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * 测试用假 ChatModel —— 按 prompt 内容路由回复，并记录全部调用
 *
 * <p>心得：测 Agent 循环的关键是"模型可回放"——把模型响应写成
 * 路由器（prompt 含"任务规划师"→ 规划清单，含"ReAct Agent"→ 推理步），
 * 就能在离线环境完整驱动 Planner/Worker/Reviewer/Reflection 全流程。</p>
 *
 * @author ibqy
 */
public class FakeChatModel implements ChatModel {

    private final Function<String, String> router;
    private final List<String> prompts = new CopyOnWriteArrayList<>();

    public FakeChatModel(Function<String, String> router) {
        this.router = router;
    }

    /** 固定回复的便捷构造 */
    public static FakeChatModel always(String reply) {
        return new FakeChatModel(prompt -> reply);
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        String prompt = request.messages().toString();
        prompts.add(prompt);
        String reply = router.apply(prompt);
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(reply))
                .metadata(dev.langchain4j.model.chat.response.ChatResponseMetadata.builder()
                        .tokenUsage(new TokenUsage(10, 5))
                        .build())
                .build();
    }

    /** 全部 prompt（断言用） */
    public List<String> prompts() {
        return List.copyOf(prompts);
    }

    /** 调用次数 */
    public int callCount() {
        return prompts.size();
    }
}
