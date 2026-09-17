package com.xb.agent.tool;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tool 工具系统测试 —— 权限校验、执行器错误分类
 *
 * 心得：权限校验必须集中，不能分散到各工具；错误反馈必须结构化，让 Agent 自己决策
 */
class ToolSystemTest {

    // ======== ToolPermission 权限校验 ========

    @Test
    void testToolPermissionGrantAndCheck() {
        ToolPermission permission = new ToolPermission();

        ToolDescriptor desc = new ToolDescriptor(
                "orderQuery", "查询订单", new String[]{"orderId"},
                "order:read", ToolDescriptor.RiskLevel.LOW, 3000, "1.0");

        // 未授权时检查失败
        assertFalse(permission.check("user1", desc));

        // 授权后检查通过
        permission.grant("user1", "order:read");
        assertTrue(permission.check("user1", desc));

        // 其他用户未授权
        assertFalse(permission.check("user2", desc));

        // 授权其他权限不影响
        permission.grant("user1", "order:write");
        assertTrue(permission.check("user1", desc));  // order:read 仍在
    }

    @Test
    void testToolPermissionMultiplePermissions() {
        ToolPermission permission = new ToolPermission();

        ToolDescriptor readDesc = new ToolDescriptor(
                "orderQuery", "查询", new String[]{}, "order:read",
                ToolDescriptor.RiskLevel.LOW, 3000, "1.0");
        ToolDescriptor writeDesc = new ToolDescriptor(
                "orderDelete", "删除", new String[]{}, "order:write",
                ToolDescriptor.RiskLevel.HIGH, 5000, "1.0");

        // 只授予读权限
        permission.grant("user1", "order:read");
        assertTrue(permission.check("user1", readDesc));
        assertFalse(permission.check("user1", writeDesc));

        // 追加写权限
        permission.grant("user1", "order:write");
        assertTrue(permission.check("user1", readDesc));
        assertTrue(permission.check("user1", writeDesc));
    }

    // ======== ToolExecutor 执行器 ========

    @Test
    void testToolExecutorToolNotFound() {
        ToolRegistry registry = new ToolRegistry();
        ToolPermission permission = new ToolPermission();
        ToolExecutor executor = new ToolExecutor(registry, permission);

        String result = executor.execute("user1", "nonexistent", "{}");
        assertTrue(result.contains("[ERROR]"));
        assertTrue(result.contains("工具不存在"));
    }

    @Test
    void testToolExecutorPermissionDenied() {
        ToolRegistry registry = new ToolRegistry();
        ToolPermission permission = new ToolPermission();
        ToolExecutor executor = new ToolExecutor(registry, permission);

        // 注册工具
        registry.register(createTestTool("testTool", "test:read"));

        // 用户无权限
        String result = executor.execute("user1", "testTool", "{}");
        assertTrue(result.contains("[ERROR]"));
        assertTrue(result.contains("无权限"));
        assertTrue(result.contains("test:read"));
    }

    @Test
    void testToolExecutorSuccess() {
        ToolRegistry registry = new ToolRegistry();
        ToolPermission permission = new ToolPermission();
        ToolExecutor executor = new ToolExecutor(registry, permission);

        // 注册工具并授权
        registry.register(createTestTool("testTool", "test:read"));
        permission.grant("user1", "test:read");

        String result = executor.execute("user1", "testTool", "{\"key\":\"value\"}");
        assertEquals("执行成功", result);
    }

    @Test
    void testToolExecutorParameterError() {
        ToolRegistry registry = new ToolRegistry();
        ToolPermission permission = new ToolPermission();
        ToolExecutor executor = new ToolExecutor(registry, permission);

        // 注册会抛 IllegalArgumentException 的工具
        registry.register(new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("paramTool", "参数测试", new String[]{},
                        "test:read", ToolDescriptor.RiskLevel.LOW, 1000, "1.0");
            }

            @Override
            public String execute(String argsJson) {
                throw new IllegalArgumentException("缺少必填参数 id");
            }
        });
        permission.grant("user1", "test:read");

        String result = executor.execute("user1", "paramTool", "{}");
        assertTrue(result.contains("[ERROR]"));
        assertTrue(result.contains("参数错误"));
        assertTrue(result.contains("缺少必填参数"));
        assertTrue(result.contains("修改入参后重试"));
    }

    @Test
    void testToolExecutorRuntimeError() {
        ToolRegistry registry = new ToolRegistry();
        ToolPermission permission = new ToolPermission();
        ToolExecutor executor = new ToolExecutor(registry, permission);

        // 注册会抛 RuntimeException 的工具
        registry.register(new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor("errorTool", "错误测试", new String[]{},
                        "test:read", ToolDescriptor.RiskLevel.LOW, 1000, "1.0");
            }

            @Override
            public String execute(String argsJson) {
                throw new RuntimeException("网络连接超时");
            }
        });
        permission.grant("user1", "test:read");

        String result = executor.execute("user1", "errorTool", "{}");
        assertTrue(result.contains("[ERROR]"));
        assertTrue(result.contains("工具执行失败"));
        assertTrue(result.contains("网络连接超时"));
        assertTrue(result.contains("网络问题"));
    }

    // ======== ToolRegistry 注册中心 ========

    @Test
    void testToolRegistryBuildToolListPrompt() {
        ToolRegistry registry = new ToolRegistry();

        registry.register(createTestTool("tool1", "test:read"));
        registry.register(createTestTool("tool2", "test:write"));

        String prompt = registry.buildToolListPrompt();
        assertTrue(prompt.contains("tool1"));
        assertTrue(prompt.contains("tool2"));
        assertTrue(prompt.contains("测试工具"));
    }

    @Test
    void testToolRegistryUnregister() {
        ToolRegistry registry = new ToolRegistry();

        registry.register(createTestTool("tool1", "test:read"));
        assertNotNull(registry.get("tool1"));
        assertEquals(1, registry.listAll().size());

        registry.unregister("tool1");
        assertNull(registry.get("tool1"));
        assertEquals(0, registry.listAll().size());

        // 取消不存在的工具不报错
        registry.unregister("nonexistent");
    }

    // ======== 辅助方法 ========

    private AgentTool createTestTool(String name, String permission) {
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(name, "测试工具", new String[]{"key"},
                        permission, ToolDescriptor.RiskLevel.LOW, 1000, "1.0");
            }

            @Override
            public String execute(String argsJson) {
                return "执行成功";
            }
        };
    }
}
