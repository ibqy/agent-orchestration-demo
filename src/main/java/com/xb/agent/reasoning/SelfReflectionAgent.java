package com.xb.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Self-Reflection：执行 → 反思 → 不合格则重试
 *
 * 心得：反思用独立 prompt 而不是塞在主 prompt 里，
 * 分开后反思标准可以单独迭代，主流程不受污染
 *
 * @author ibqy
 */
public class SelfReflectionAgent implements ReasoningAgent {

    private static final Logger log = LoggerFactory.getLogger(SelfReflectionAgent.class);

    private static final String REFLECT_PROMPT = """
            任务目标: %s
            执行结果: %s

            检查结果:
            1. 是否完全满足目标
            2. 是否有遗漏信息
            3. 是否存在编造内容

            合格输出 PASS，不合格输出 FAIL: 原因
            """;

    private final ReasoningAgent delegate;   // 委托实际执行（如 ReActAgent）
    private final ChatModel model;
    private final int maxRetries;

    public SelfReflectionAgent(ReasoningAgent delegate, ChatModel model, int maxRetries) {
        this.delegate = delegate;
        this.model = model;
        this.maxRetries = maxRetries;
    }

    @Override
    public String execute(String goal) {
        String feedback = "";
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            String result = delegate.execute(feedback.isEmpty() ? goal : goal + "\n上次失败原因: " + feedback);
            log.info("[Reflect] 第 {} 次执行完成", attempt);

            String verdict = model.chat(REFLECT_PROMPT.formatted(goal, result)).trim();
            if (verdict.startsWith("PASS")) {
                return result;
            }
            feedback = verdict.replaceFirst("^FAIL:?", "").trim();
            log.info("[Reflect] 不合格: {}", feedback);
        }
        return "多次执行均未通过自检: " + feedback;
    }
}
