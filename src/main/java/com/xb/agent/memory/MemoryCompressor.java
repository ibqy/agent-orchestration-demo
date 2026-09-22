package com.xb.agent.memory;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 记忆压缩 —— 上下文逼近窗口上限时 LLM 摘要
 *
 * 心得：压缩阈值要留提前量（如 70% 窗口就压），
 * 压缩本身也要消耗 token，等 100% 才压就来不及了
 *
 * @author ibqy
 */
@Component
public class MemoryCompressor {

    private static final Logger log = LoggerFactory.getLogger(MemoryCompressor.class);

    private static final String SUMMARY_PROMPT = """
            把以下对话历史压缩为简洁摘要，保留:
            1. 任务目标和已完成的关键结论
            2. 重要的数据/编号/参数
            3. 未解决的遗留问题
            删除客套话和过程性描述。

            对话历史:
            %s
            """;

    private final ChatModel model;

    public MemoryCompressor(ChatModel model) {
        this.model = model;
    }

    /**
     * 粗估消息列表的 token 总量
     * @param messages 消息列表
     * @return 估算的 token 数
     */
    public int estimateTokens(List<String> messages) {
        return messages.stream().mapToInt(m -> m.length() / 2 + 1).sum();
    }

    /**
     * 超阈值时用 LLM 压缩对话历史
     * @param messages 原始对话列表
     * @param maxTokens token 上限
     * @return 压缩后的消息列表（可能是摘要）
     */
    public List<String> compressIfNeeded(List<String> messages, int maxTokens) {
        int current = estimateTokens(messages);
        if (current <= maxTokens * 7 / 10) {
            return messages;  // 低于 70% 阈值不动
        }

        String history = String.join("\n", messages);
        String summary = model.chat(SUMMARY_PROMPT.formatted(history));
        log.info("[Compressor] 压缩 {} tokens → 约 {} tokens", current, summary.length() / 2);

        return List.of("[历史摘要] " + summary);
    }
}
