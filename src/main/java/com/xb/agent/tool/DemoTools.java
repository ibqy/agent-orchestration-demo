package com.xb.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 学习用示例工具集 —— 注册中心 + 权限 + 回滚演示
 *
 * 心得：工具本身逻辑越简单越好（演示架构），
 * 复杂度应该都在注册/权限/熔断这些横切关注点上
 */
@Component
public class DemoTools implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(DemoTools.class);

    private final ToolRegistry registry;
    private final ToolPermission permission;

    public DemoTools(ToolRegistry registry, ToolPermission permission) {
        this.registry = registry;
        this.permission = permission;
    }

    @Override
    public void afterSingletonsInstantiated() {
        registry.register(orderQueryTool());
        registry.register(orderDeleteTool());
        registry.register(flakyTool());
        log.info("[DemoTools] 3 个示例工具已注册");

        // 教学演示：为默认用户授予所有工具权限，生产环境应从权限系统加载
        permission.grant("default", "order:read", "order:write", "api:call");
        permission.grant("demo", "order:read", "order:write", "api:call");
        log.info("[DemoTools] 已为 default/demo 用户授予演示权限");
    }

    /** 查询工具: 只读，LOW 风险 */
    private AgentTool orderQueryTool() {
        return new AgentTool() {
            private final Map<String, String> orders = Map.of(
                    "A1001", "订单A1001: 3件商品, 总额298元, 已发货",
                    "A1002", "订单A1002: 1件商品, 总额1299元, 待支付");

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        "orderQuery", "查询订单信息", new String[]{"orderId"},
                        "order:read", ToolDescriptor.RiskLevel.LOW, 3000, "1.0");
            }

            @Override
            public String execute(String argsJson) throws Exception {
                String orderId = ToolArgs.extract(argsJson, "orderId");
                if (orderId == null) throw new IllegalArgumentException("缺少 orderId 参数");
                String result = orders.get(orderId);
                if (result == null) return "订单 " + orderId + " 不存在";
                return result;
            }
        };
    }

    /** 高危工具: 需要写权限 + 支持回滚 */
    private AgentTool orderDeleteTool() {
        return new AgentTool() {
            private final Map<String, String> liveOrders = new ConcurrentHashMap<>(Map.of(
                    "A1001", "订单A1001", "A1002", "订单A1002"));

            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        "orderDelete", "删除订单(高危)", new String[]{"orderId"},
                        "order:write", ToolDescriptor.RiskLevel.HIGH, 5000, "1.0");
            }

            @Override
            public String execute(String argsJson) throws Exception {
                String orderId = ToolArgs.extract(argsJson, "orderId");
                if (orderId == null) throw new IllegalArgumentException("缺少 orderId 参数");
                String removed = liveOrders.remove(orderId);
                return removed != null ? "已删除 " + orderId : "订单 " + orderId + " 不存在";
            }

            @Override
            public String undo(String argsJson) throws Exception {
                String orderId = ToolArgs.extract(argsJson, "orderId");
                liveOrders.put(orderId, "订单" + orderId + "(已恢复)");
                return "已回滚: 恢复订单 " + orderId;
            }
        };
    }

    /** 模拟网络不稳定的工具: 演示熔断 */
    private AgentTool flakyTool() {
        return new AgentTool() {
            @Override
            public ToolDescriptor descriptor() {
                return new ToolDescriptor(
                        "externalApi", "调用外部接口(不稳定)", new String[]{"query"},
                        "api:call", ToolDescriptor.RiskLevel.MEDIUM, 2000, "1.0");
            }

            @Override
            public String execute(String argsJson) throws Exception {
                if (ThreadLocalRandom.current().nextInt(10) < 7) {
                    throw new RuntimeException("网络超时");
                }
                return "外部接口返回: OK";
            }
        };
    }
}
