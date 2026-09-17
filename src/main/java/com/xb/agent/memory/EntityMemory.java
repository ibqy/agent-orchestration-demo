package com.xb.agent.memory;

import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 实体记忆 —— 结构化存关键实体（用户名/订单号/业务ID）
 *
 * 心得：实体记忆和向量记忆的本质区别是"精确查询 vs 相似检索"。
 * 订单号这种字段过向量检索是浪费，结构化直接查就 1ms
 */
@Repository
public class EntityMemory {

    /** 实体类型 → (用户ID → 实体值) */
    private final Map<String, Map<String, String>> entities = new ConcurrentHashMap<>();

    public void save(String userId, String entityType, String value) {
        entities.computeIfAbsent(entityType, k -> new ConcurrentHashMap<>())
                .put(userId, value);
    }

    public String get(String userId, String entityType) {
        return entities.getOrDefault(entityType, Map.of()).get(userId);
    }

    /** 注入 SystemPrompt 的实体块 */
    public String buildPromptBlock(String userId) {
        if (entities.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        entities.forEach((type, users) -> {
            String v = users.get(userId);
            if (v != null) {
                if (sb.isEmpty()) sb.append("已知用户关键实体:\n");
                sb.append("- ").append(type).append(": ").append(v).append('\n');
            }
        });
        return sb.toString();
    }
}
