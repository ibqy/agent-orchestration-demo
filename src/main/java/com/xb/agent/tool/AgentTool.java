package com.xb.agent.tool;

/**
 * Agent 工具统一接口
 *
 * 心得：undo 回滚设计成可选（default 抛异常），
 * 只读工具不用写空实现，写操作工具必须实现
 *
 * @author ibqy
 */
public interface AgentTool {

    /** 获取工具描述符（注册元数据） */
    ToolDescriptor descriptor();

    /**
     * 执行工具
     * @param argsJson 入参 JSON
     * @return 执行结果（观察文本）
     * @throws Exception 执行异常
     */
    String execute(String argsJson) throws Exception;

    /**
     * 撤销已执行的操作（高危工具必须实现）
     * @param argsJson 入参 JSON
     * @return 撤销结果
     * @throws Exception 撤销异常
     */
    default String undo(String argsJson) throws Exception {
        throw new UnsupportedOperationException(descriptor().getName() + " 不支持回滚");
    }
}
