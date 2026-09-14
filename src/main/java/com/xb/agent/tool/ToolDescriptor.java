package com.xb.agent.tool;

/**
 * 工具描述符 —— 注册中心的元数据
 *
 * 心得：JSON Schema 不用自己手写，从参数反推生成。
 * 版本字段必须有，工具改了签名旧任务回放会炸
 */
public class ToolDescriptor {

    public enum RiskLevel { LOW, MEDIUM, HIGH }

    private String name;
    private String description;
    private String[] params;            // 参数名列表
    private String permission;          // 权限标签
    private RiskLevel risk;
    private int timeoutMs;
    private String version;

    public ToolDescriptor(String name, String description, String[] params,
                          String permission, RiskLevel risk, int timeoutMs, String version) {
        this.name = name;
        this.description = description;
        this.params = params;
        this.permission = permission;
        this.risk = risk;
        this.timeoutMs = timeoutMs;
        this.version = version;
    }

    /** 给 LLM 看的工具清单片段 */
    public String toPromptSnippet() {
        return "- " + name + "(" + String.join(", ", params) + "): " + description
                + " [risk=" + risk + ", v" + version + "]";
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
    public String[] getParams() { return params; }
    public String getPermission() { return permission; }
    public RiskLevel getRisk() { return risk; }
    public int getTimeoutMs() { return timeoutMs; }
    public String getVersion() { return version; }
}
