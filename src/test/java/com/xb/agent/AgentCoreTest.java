package com.xb.agent;

import com.xb.agent.multi.SubTask;
import com.xb.agent.multi.TaskPlan;
import com.xb.agent.reasoning.ActionParser;
import com.xb.agent.reasoning.AgentAction;
import com.xb.agent.tool.ToolDescriptor;
import com.xb.agent.tool.ToolRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 不依赖外部服务的核心逻辑测试
 *
 * ActionParser 容错解析 / TaskPlan 依赖调度 / 工具注册中心
 */
class AgentCoreTest {

    // ======== ActionParser ========

    @Test
    void testParseToolCall() {
        String llm = """
                THOUGHT: 需要先查订单
                ACTION: {"tool": "orderQuery", "args": {"orderId": "A1001"}}
                """;
        AgentAction action = ActionParser.parse(llm);
        assertTrue(action.isToolCall());
        assertEquals("orderQuery", action.getToolName());
        assertEquals("{\"orderId\": \"A1001\"}", action.getToolArgs());
    }

    @Test
    void testParseAnswer() {
        String llm = """
                THOUGHT: 信息已齐全
                ANSWER: 订单 A1001 总额 298 元已发货
                """;
        AgentAction action = ActionParser.parse(llm);
        assertTrue(action.isFinal());
        assertFalse(action.isToolCall());
        assertEquals("订单 A1001 总额 298 元已发货", action.getFinalAnswer());
    }

    @Test
    void testParseMalformed() {
        // 格式错误时不抛异常，降级为 retry
        AgentAction action = ActionParser.parse("随便说点什么");
        assertTrue(action.isNeedRetry());
        assertNull(action.getToolName());
    }

    // ======== TaskPlan 依赖调度 ========

    @Test
    void testDependencyScheduling() {
        TaskPlan plan = new TaskPlan("t1", "查询并汇总订单");
        SubTask t1 = new SubTask("1", "查询订单");
        SubTask t2 = new SubTask("2", "统计金额");
        t2.getDependsOn().add("1");
        SubTask t3 = new SubTask("3", "生成报告");
        t3.getDependsOn().addAll(List.of("1", "2"));
        plan.addSubTask(t1);
        plan.addSubTask(t2);
        plan.addSubTask(t3);

        // 初始只有 t1 可执行
        assertEquals(1, plan.runnableTasks().size());
        assertEquals("1", plan.runnableTasks().get(0).getId());

        // t1 完成后 t2 可执行
        t1.setStatus(SubTask.Status.DONE);
        List<SubTask> batch = plan.runnableTasks();
        assertEquals(1, batch.size());
        assertEquals("2", batch.get(0).getId());

        // t1+t2 都完成后 t3 可执行
        t2.setStatus(SubTask.Status.DONE);
        assertEquals(1, plan.runnableTasks().size());
        assertEquals("3", plan.runnableTasks().get(0).getId());

        // 全部完成
        t3.setStatus(SubTask.Status.DONE);
        assertTrue(plan.allFinished());
    }

    // ======== 工具注册中心 ========

    @Test
    void testToolRegistryDynamic() {
        ToolRegistry registry = new ToolRegistry();

        registry.register(new com.xb.agent.tool.AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("demoTool", "演示", new String[]{"x"},
                        "demo:read", ToolDescriptor.RiskLevel.LOW, 1000, "1.0");
            }

            @Override
            public String execute(String argsJson) {
                return "ok";
            }
        });

        assertEquals(1, registry.listAll().size());
        assertNotNull(registry.get("demoTool"));
        assertTrue(registry.buildToolListPrompt().contains("demoTool"));

        // 动态下线
        registry.unregister("demoTool");
        assertNull(registry.get("demoTool"));
    }
}
