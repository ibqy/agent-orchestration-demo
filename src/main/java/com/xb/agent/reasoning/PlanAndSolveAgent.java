package com.xb.agent.reasoning;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Plan-and-Solve：先一次性生成完整计划，再逐步执行
 *
 * 心得：初始规划容易"想当然"，所以每步执行完要校验，
 * 偏差时把偏差信息喂回 LLM 做计划修正（Re-Plan）
 */
public class PlanAndSolveAgent implements ReasoningAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanAndSolveAgent.class);

    private static final String PLAN_PROMPT = """
            把以下任务拆解为有序步骤，每步一行，格式:
            1. 步骤描述

            任务: %s
            """;

    private static final String VERIFY_PROMPT = """
            任务计划:
            %s

            已完成步骤:
            %s

            当前计划是否需要修正？如果一切正常，只输出 NO；否则输出修正后的完整计划。
            """;

    private final ChatModel model;
    private final ToolInvoker toolInvoker;

    public PlanAndSolveAgent(ChatModel model, ToolInvoker toolInvoker) {
        this.model = model;
        this.toolInvoker = toolInvoker;
    }

    @Override
    public String execute(String goal) {
        // 1. 生成初始计划
        String[] plan = model.chat(PLAN_PROMPT.formatted(goal))
                .lines()
                .filter(l -> l.matches("\\s*\\d+\\..+"))
                .map(l -> l.replaceFirst("^\\s*\\d+\\.\\s*", ""))
                .toArray(String[]::new);
        log.info("[P&S] 初始计划 {} 步", plan.length);

        StringBuilder done = new StringBuilder();
        String[] current = plan;

        // 2. 逐步执行
        for (int i = 0; i < current.length; i++) {
            String step = current[i];
            log.info("[P&S] 执行 {}/{}: {}", i + 1, current.length, step);

            // 让 LLM 决定本步是工具调用还是纯推理
            AgentAction action = ActionParser.parse(model.chat(stepPrompt(step)));
            if (action.isToolCall()) {
                String obs = toolInvoker.invoke(action.getToolName(), action.getToolArgs());
                done.append("步骤").append(i + 1).append(": ").append(step)
                    .append(" → ").append(obs).append('\n');
            } else {
                done.append("步骤").append(i + 1).append(": ").append(step)
                    .append(" → ").append(action.getFinalAnswer() != null
                            ? action.getFinalAnswer() : action.getThought()).append('\n');
            }

            // 3. 每步校验，偏差则 Re-Plan
            if (i < current.length - 1) {
                String verify = model.chat(VERIFY_PROMPT.formatted(String.join("\n", current), done));
                if (!verify.trim().startsWith("NO")) {
                    current = verify.lines()
                            .filter(l -> l.matches("\\s*\\d+\\..+"))
                            .map(l -> l.replaceFirst("^\\s*\\d+\\.\\s*", ""))
                            .toArray(String[]::new);
                    log.info("[P&S] 计划修正为 {} 步", current.length);
                }
            }
        }

        // 4. 汇总
        return model.chat("根据以下执行记录总结任务结果:\n" + done);
    }

    private String stepPrompt(String step) {
        return """
                执行以下子步骤。如需工具，输出:
                ACTION: {"tool": "工具名", "args": {...}}
                否则直接给结果文本。

                步骤: %s
                """.formatted(step);
    }
}
