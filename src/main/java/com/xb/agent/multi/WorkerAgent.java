package com.xb.agent.multi;

import com.xb.agent.guard.HITLRejectedException;
import com.xb.agent.reasoning.ReasoningAgent;
import com.xb.agent.reasoning.ReasoningAgentFactory;
import com.xb.agent.reasoning.ReasoningMode;
import com.xb.agent.reasoning.StepListener;
import com.xb.agent.reasoning.ToolInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Worker 执行 Agent：接收单个子任务，按指定推理模式执行，返回结果
 *
 * 心得：Worker 里不再硬编码 ReAct——多 Agent 的价值是"角色分工"，
 * 不是每个角色都要发明新推理逻辑；具体用哪种推理模式由调用方
 * （任务级 mode 参数）通过 {@link ReasoningAgentFactory} 决定
 *
 * @author ibqy
 */
@Component
public class WorkerAgent {

    private static final Logger log = LoggerFactory.getLogger(WorkerAgent.class);

    private final ReasoningAgentFactory agentFactory;

    public WorkerAgent(ReasoningAgentFactory agentFactory) {
        this.agentFactory = agentFactory;
    }

    /**
     * 用指定推理模式执行单个子任务
     *
     * @param task        待执行子任务
     * @param toolInvoker 工具调用回调（含防护管道）
     * @param mode        推理模式（null = 用配置默认值）
     * @param listener    步骤监听器（SSE 推送，可为 noop）
     * @return 执行结果文本
     */
    public String execute(SubTask task, ToolInvoker toolInvoker, ReasoningMode mode, StepListener listener) {
        log.info("[Worker] 开始子任务 {}: {} (mode={})", task.getId(), task.getGoal(), mode);
        task.setStatus(SubTask.Status.RUNNING);

        try {
            ReasoningAgent agent = agentFactory.create(mode, toolInvoker, listener);
            String result = agent.execute(task.getGoal());
            task.setStatus(SubTask.Status.DONE);
            task.setResult(result);
            log.info("[Worker] 子任务 {} 完成", task.getId());
            return result;
        } catch (HITLRejectedException e) {
            // 人工拒绝必须穿透到任务层终止，不能被当成普通执行失败吞掉
            task.setStatus(SubTask.Status.FAILED);
            task.setResult(e.getMessage());
            log.warn("[Worker] 子任务 {} 因人工拒绝而终止", task.getId());
            throw e;
        } catch (Exception e) {
            task.setStatus(SubTask.Status.FAILED);
            task.setResult("执行失败: " + e.getMessage());
            log.error("[Worker] 子任务 {} 失败", task.getId(), e);
            return task.getResult();
        }
    }
}
