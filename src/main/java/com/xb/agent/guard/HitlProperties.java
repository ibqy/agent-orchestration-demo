package com.xb.agent.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * HITL（Human-in-the-Loop）配置 —— 从 application.yml agent.hitl.* 绑定
 *
 * <p>心得：高危工具清单放配置而不是硬编码——安全策略是运维决策，
 * 不该跟着发版走。application.yml 里其实一直有 {@code agent.hitl.high-risk-tools}
 * 这个配置项，但早期没有 Properties 类绑定它，代码实际读的是
 * {@code ToolDescriptor.risk == HIGH}，"看起来支持"其实是断的。
 * 现在两条判断并用：命中配置清单或风险等级为 HIGH，都触发人工审批。</p>
 *
 * @author ibqy
 */
@ConfigurationProperties(prefix = "agent.hitl")
public class HitlProperties {

    /** 命中即触发人工审批的工具名清单（如 dropTable, deleteOrder, refund） */
    private Set<String> highRiskTools = new LinkedHashSet<>();

    /** 审批等待超时（分钟），超时按拒绝处理——不能让人工通道成为无限期挂起的后门 */
    private long approvalTimeoutMinutes = 10;

    public Set<String> getHighRiskTools() { return highRiskTools; }
    public void setHighRiskTools(Set<String> highRiskTools) { this.highRiskTools = highRiskTools; }
    public long getApprovalTimeoutMinutes() { return approvalTimeoutMinutes; }
    public void setApprovalTimeoutMinutes(long approvalTimeoutMinutes) { this.approvalTimeoutMinutes = approvalTimeoutMinutes; }

    /** 是否命中高危清单 */
    public boolean isHighRisk(String toolName) {
        return highRiskTools != null && highRiskTools.contains(toolName);
    }
}
