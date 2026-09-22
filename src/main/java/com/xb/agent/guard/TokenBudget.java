package com.xb.agent.guard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Token 预算 —— 超预算强制终止
 *
 * 心得：预算检查放在每次 LLM 调用前后，
 * 宁可提前停也别等账单出来吓一跳
 *
 * @author ibqy
 */
@Component
public class TokenBudget {

    private static final String KEY = "guard:tokens:%s";

    private final StringRedisTemplate redis;

    public TokenBudget(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 累加 token 消耗量
     * @param taskId 任务ID
     * @param tokens 本次消耗的 token 数
     */
    public void add(String taskId, long tokens) {
        String key = KEY.formatted(taskId);
        redis.opsForValue().increment(key, tokens);
        redis.expire(key, Duration.ofHours(2));
    }

    /**
     * 粗估 token 数量
     * @param prompt 输入文本
     * @return 估算的 token 数（字符数/2）
     */
    public long estimate(String prompt) {
        return prompt == null ? 0 : prompt.length() / 2L;
    }

    /**
     * 检查是否超出 token 预算
     * @param taskId 任务ID
     * @param budget 预算上限
     * @return 超预算返回提示文本，否则返回 null
     */
    public String checkBudget(String taskId, long budget) {
        String used = redis.opsForValue().get(KEY.formatted(taskId));
        if (used != null && Long.parseLong(used) >= budget) {
            return "[GUARD] token 预算耗尽（" + budget + "），任务终止。请基于已有信息直接回答";
        }
        return null;
    }
}
