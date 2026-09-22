package com.xb.agent.multi;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Reviewer 评审 Agent：校验 Worker 结果，决策通过/重试/终止
 *
 * 心得：评审结论要结构化（PASS/RETRY/ABORT），
 * 别让 Reviewer 输出一大段文字再靠人解读
 *
 * @author ibqy
 */
@Component
public class ReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(ReviewerAgent.class);

    public enum Verdict { PASS, RETRY, ABORT }

    private static final String REVIEW_PROMPT = """
            子任务目标: %s
            执行结果: %s
            
            检查:
            1. 结果是否完成了目标
            2. 是否存在编造/幻觉内容
            3. 数据是否合理
            
            输出结论（只输出一个单词）:
            PASS - 通过
            RETRY - 结果可疑，建议重试
            ABORT - 目标无法实现，终止
            """;

    private final ChatModel model;

    public ReviewerAgent(ChatModel model) {
        this.model = model;
    }

    /**
     * 让 LLM 评审子任务执行结果，返回结构化结论
     * @param task 待评审的子任务
     * @return PASS/RETRY/ABORT 三选一
     */
    public Verdict review(SubTask task) {
        String output = model.chat(
                REVIEW_PROMPT.formatted(task.getGoal(), task.getResult())).trim().toUpperCase();
        log.info("[Reviewer] 子任务 {} 评审: {}", task.getId(), output);

        if (output.contains("RETRY")) return Verdict.RETRY;
        if (output.contains("ABORT")) return Verdict.ABORT;
        return Verdict.PASS;
    }
}
