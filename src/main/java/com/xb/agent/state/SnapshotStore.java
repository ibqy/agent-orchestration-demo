package com.xb.agent.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;

/**
 * 快照仓库 —— Redis 持久化
 *
 * 心得1: 每次状态变更都 save，别攒批 —— 服务随时可能挂
 * 心得2: TTL 给 7 天，任务快照不是永久数据，过期自动清理省内存
 */
@Repository
public class SnapshotStore {

    private static final Logger log = LoggerFactory.getLogger(SnapshotStore.class);
    private static final String KEY_PREFIX = "agent:task:";
    private static final Duration TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper = new ObjectMapper();

    public SnapshotStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void save(TaskSnapshot snapshot) {
        try {
            String json = mapper.writeValueAsString(snapshot);
            redis.opsForValue().set(KEY_PREFIX + snapshot.getTaskId(), json, TTL);
            log.debug("[Snapshot] 已保存 task={} state={}", snapshot.getTaskId(), snapshot.getState());
        } catch (Exception e) {
            // 快照失败不能中断主流程，只记录 —— 但生产要告警
            log.error("[Snapshot] 保存失败 task={}", snapshot.getTaskId(), e);
        }
    }

    /** 读取快照（断点续跑入口） */
    public TaskSnapshot load(String taskId) {
        String json = redis.opsForValue().get(KEY_PREFIX + taskId);
        if (json == null) return null;
        try {
            return mapper.readValue(json, TaskSnapshot.class);
        } catch (Exception e) {
            log.error("[Snapshot] 反序列化失败 task={}", taskId, e);
            return null;
        }
    }

    public void delete(String taskId) {
        redis.delete(KEY_PREFIX + taskId);
    }
}
