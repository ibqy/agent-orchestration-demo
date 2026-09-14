package com.xb.agent.multi;

import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Planner 规划 Agent：拆解子任务、识别依赖、分配 Worker
 *
 * 心得：让 LLM 输出带编号+依赖的行式格式，比直接要求 JSON 稳得多，
 * 解析容错写 10 行就够
 */
@Component
public class PlannerAgent {

    private static final Logger log = LoggerFactory.getLogger(PlannerAgent.class);

    private static final String PLAN_PROMPT = """
            你是任务规划师。把用户需求拆解为可执行的子任务清单。
            每行格式:
            <编号> | <子任务目标> | <依赖的子任务编号，无依赖则写 ->
            
            示例:
            1 | 查询用户订单列表 | -
            2 | 统计订单总金额 | 1
            3 | 生成汇总报告 | 1,2
            
            用户需求: %s
            
            只输出子任务清单，不要其他解释。
            """;

    private final ChatModel model;

    public PlannerAgent(ChatModel model) {
        this.model = model;
    }

    public TaskPlan createPlan(String taskId, String userGoal) {
        TaskPlan plan = new TaskPlan(taskId, userGoal);
        String output = model.chat(PLAN_PROMPT.formatted(userGoal));
        log.info("[Planner] 原始规划输出:\n{}", output);

        for (String line : output.lines()) {
            String[] parts = line.split("\\|");
            if (parts.length < 2) continue;

            String id = parts[0].trim().replaceAll("\\D", "");
            String goal = parts[1].trim();
            if (id.isEmpty() || goal.isEmpty()) continue;

            SubTask task = new SubTask(id, goal);
            if (parts.length >= 3) {
                String deps = parts[2].trim();
                if (!deps.isEmpty() && !deps.equals("-")) {
                    for (String d : deps.split(",")) {
                        String depId = d.trim().replaceAll("\\D", "");
                        if (!depId.isEmpty()) task.getDependsOn().add(depId);
                    }
                }
            }
            plan.addSubTask(task);
        }

        log.info("[Planner] 拆解出 {} 个子任务", plan.getSubTasks().size());
        return plan;
    }
}
