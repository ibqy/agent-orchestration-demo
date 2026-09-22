package com.xb.agent.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具注册中心 —— 运行时动态注册/下线，不重启服务
 *
 * 心得：注册中心的核心是把"工具发现"和"工具执行"分开，
 * LLM 只看描述符清单，执行时才绑定具体实现
 *
 * @author ibqy
 */
@Component
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

    private final Map<String, AgentTool> tools = new ConcurrentHashMap<>();

    /**
     * 注册工具到注册中心
     * @param tool 待注册的工具
     */
    public void register(AgentTool tool) {
        tools.put(tool.descriptor().getName(), tool);
        log.info("[Registry] 注册工具 {} v{}", tool.descriptor().getName(), tool.descriptor().getVersion());
    }

    /** 动态下线（生产: 配置中心推送触发） */
    public void unregister(String name) {
        AgentTool removed = tools.remove(name);
        log.info("[Registry] 下线工具 {} {}", name, removed != null ? "成功" : "(不存在)");
    }

    /**
     * 按名称获取已注册工具
     * @param name 工具名称
     * @return 工具实例，不存在返回 null
     */
    public AgentTool get(String name) {
        return tools.get(name);
    }

    /**
     * 列出所有已注册工具的描述符
     * @return 工具描述符列表
     */
    public List<ToolDescriptor> listAll() {
        return tools.values().stream().map(AgentTool::descriptor).toList();
    }

    /** 给 LLM 的工具清单 prompt 片段 */
    public String buildToolListPrompt() {
        StringBuilder sb = new StringBuilder("可用工具:\n");
        tools.values().forEach(t -> sb.append(t.descriptor().toPromptSnippet()).append('\n'));
        return sb.toString();
    }
}
