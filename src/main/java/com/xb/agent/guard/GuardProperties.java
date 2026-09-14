package com.xb.agent.guard;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 防护参数 —— 从 application.yml agent.guard.* 绑定 */
@ConfigurationProperties(prefix = "agent.guard")
public class GuardProperties {
    private int maxToolCalls = 8;
    private long tokenBudget = 100_000;
    private int maxConcurrentPerUser = 3;

    public int getMaxToolCalls() { return maxToolCalls; }
    public long getTokenBudget() { return tokenBudget; }
    public int getMaxConcurrentPerUser() { return maxConcurrentPerUser; }
    public void setMaxToolCalls(int maxToolCalls) { this.maxToolCalls = maxToolCalls; }
    public void setTokenBudget(long tokenBudget) { this.tokenBudget = tokenBudget; }
    public void setMaxConcurrentPerUser(int maxConcurrentPerUser) { this.maxConcurrentPerUser = maxConcurrentPerUser; }
}
