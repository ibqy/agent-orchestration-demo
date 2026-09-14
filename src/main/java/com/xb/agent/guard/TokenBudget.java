package com.xb.agent.guard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token 预算 —— 超预算强制终止
 *
 * 心得：预算检查放在每次 LLM 调用前后，
 * 宁可提前停也别等账单出来吓一跳
 */
@Component
public class TokenBudget {

    private static final String KEY = "guard:tokens:%s";

    private final StringRedisTemplate redis;

    public TokenBudget(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void add(String taskId, long tokens) {
        String key = KEY.formatted(taskId);
        redis.opsForValue().increment(key, tokens);
        redis.expire(key, Duration.ofHours(2));
    }

    /** 粗估: prompt 字符数/2 */
    public long estimate(String prompt) {
        return prompt == null ? 0 : prompt.length() / 2L;
    }

    /** 检查是否超预算，超了返回提示 */
    public String checkBudget(String taskId, long budget) {
        String used = redis.opsForValue().get(KEY.formatted(taskId));
        if (used != null && Long.parseLong(used) >= budget) {
            return "[GUARD] token 预算耗尽（" + budget + "），任务终止。请基于已有信息直接回答";
        }
        return null;
    }
}
