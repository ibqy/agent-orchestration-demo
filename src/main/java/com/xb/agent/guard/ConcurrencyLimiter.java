package com.xb.agent.guard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 并发限制 —— 单用户并发任务上限
 *
 * 心得：Redis increment + expire 做计数器，
 * 比信号量简单且天然跨实例（多 Pod 部署也要限得住）
 */
@Component
public class ConcurrencyLimiter {

    private static final String KEY = "guard:conc:%s";

    private final StringRedisTemplate redis;

    public ConcurrencyLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /** 尝试占一个并发槽，成功 true */
    public boolean tryAcquire(String userId, int max) {
        String key = KEY.formatted(userId);
        Long current = redis.opsForValue().increment(key);
        redis.expire(key, Duration.ofMinutes(10));
        return current != null && current <= max;
    }

    public void release(String userId) {
        redis.opsForValue().decrement(KEY.formatted(userId));
    }
}
