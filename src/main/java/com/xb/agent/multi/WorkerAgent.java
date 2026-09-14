package com.xb.agent.multi;

import dev.langchain4j.model.chat.ChatModel;
import com.xb.agent.reasoning.ReActAgent;
import com.xb.agent.reasoning.ToolInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Worker 执行 Agent：接收单个子任务，ReAct 循环调工具，返回结果
 *
 * 心得：Worker 里直接复用 ReActAgent，不要另写一套循环 ——
 * 多 Agent 的价值是"角色分工"，不是每个角色都要发明新推理逻辑
 */
@Component
public class WorkerAgent {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgent.class);

    private final ChatModel model;

    public WorkerAgent(ChatModel model) {
        this.model = model;
    }

    public String execute(SubTask task, ToolInvoker toolInvoker) {
        log.info("[Worker] 开始子任务 {}: {}", task.getId(), task.getGoal());
        task.setStatus(SubTask.Status.RUNNING);

        try {
            ReActAgent react = new ReActAgent(model, toolInvoker, 5);
            String result = react.execute(task.getGoal());
            task.setStatus(SubTask.Status.DONE);
            task.setResult(result);
            log.info("[Worker] 子任务 {} 完成", task.getId());
            return result;
        } catch (Exception e) {
            task.setStatus(SubTask.Status.FAILED);
            task.setResult("执行失败: " + e.getMessage());
            log.error("[Worker] 子任务 {} 失败", task.getId(), e);
            return task.getResult();
        }
    }
}
