package com.xb.agent.observe;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 计量包装 ChatModel —— 装饰器模式给任意 ChatModel 装上"电表"
 *
 * <p>心得：LangChain4j 1.x 的 {@code ChatModel} 所有便捷方法（chat(String) 等）
 * 最终都汇到 {@link #chat(ChatRequest)}，所以只需包这一个方法，
 * 全部调用路径的用量都能收到——这是选装饰点的关键。</p>
 *
 * <p>为什么不改业务代码逐个记录？因为计量是横切关注点：
 * Planner/Worker/Reviewer/MemoryCompressor 四个调用点改一遍，
 * 以后新增第五个还会漏。装饰器挂在 Bean 上，一次装配全局生效。</p>
 *
 * @author ibqy
 */
public class UsageCapturingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AgentMetrics metrics;

    public UsageCapturingChatModel(ChatModel delegate, AgentMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        long start = System.nanoTime();
        try {
            ChatResponse response = delegate.chat(request);
            metrics.recordLlmCall(System.nanoTime() - start, response.tokenUsage());
            return response;
        } catch (RuntimeException e) {
            // 失败的调用也要记延迟——超时/报错的耗时往往正是排查线索
            metrics.recordLlmCall(System.nanoTime() - start, null);
            throw e;
        }
    }

    /** 暴露被包装的原始模型（测试/调试用） */
    public ChatModel unwrap() {
        return delegate;
    }
}
