package com.xb.agent.tool;

/**
 * Agent 工具统一接口
 *
 * 心得：undo 回滚设计成可选（default 抛异常），
 * 只读工具不用写空实现，写操作工具必须实现
 */
public interface AgentTool {

    ToolDescriptor descriptor();

    /** 执行工具 */
    String execute(String argsJson) throws Exception;

    /** 撤销已执行的操作（高危工具必写） */
    default String undo(String argsJson) throws Exception {
        throw new UnsupportedOperationException(descriptor().getName() + " 不支持回滚");
    }
}
