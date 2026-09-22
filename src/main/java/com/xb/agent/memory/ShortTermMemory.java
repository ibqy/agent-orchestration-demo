package com.xb.agent.memory;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.List;

/**
 * 短时会话记忆 —— 当前任务的对话/思考链
 *
 * 心得：ChatMemory 只管本次对话上下文，和长时记忆是两个东西；
 * 会话过期交给 Redis TTL，不自己写清理任务
 *
 * @author ibqy
 */
@Repository
public class ShortTermMemory {

    private static final String KEY = "agent:short:%s";
    private static final Duration TTL = Duration.ofHours(2);

    private final StringRedisTemplate redis;

    public ShortTermMemory(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 追加一条对话记录
     * @param sessionId 会话ID
     * @param role 角色（user/assistant）
     * @param content 消息内容
     */
    public void append(String sessionId, String role, String content) {
        String entry = role + ":" + content;
        redis.opsForList().rightPush(KEY.formatted(sessionId), entry);
        redis.expire(KEY.formatted(sessionId), TTL);
    }

    /** 近 n 条记忆 */
    public List<String> recent(String sessionId, int n) {
        List<String> all = redis.opsForList().range(KEY.formatted(sessionId), 0, -1);
        if (all == null) return List.of();
        return all.size() <= n ? all : all.subList(all.size() - n, all.size());
    }

    /**
     * 清空指定会话的全部记忆
     * @param sessionId 会话ID
     */
    public void clear(String sessionId) {
        redis.delete(KEY.formatted(sessionId));
    }
}
