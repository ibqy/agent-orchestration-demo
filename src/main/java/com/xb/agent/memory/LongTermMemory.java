package com.xb.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 长时记忆 —— 用户长期知识（向量检索召回）
 *
 * 心得：长时记忆写入时机选"任务完成时抽取"而不是每轮对话都写，
 * 否则库很快被琐碎内容灌满，召回质量下降
 *
 * @author ibqy
 */
@Repository
public class LongTermMemory {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemory.class);

    /** 记忆条目 */
    public record MemoryItem(String id, String userId, String content, long createdAt) {}

    // 学习项目用内存模拟向量检索，生产替换 Milvus EmbeddingStore
    private final Map<String, List<MemoryItem>> store = new ConcurrentHashMap<>();
    private final Map<String, float[]> vectors = new ConcurrentHashMap<>();

    /**
     * 写入一条长时记忆
     * @param userId 用户ID
     * @param content 记忆内容
     */
    public void remember(String userId, String content) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        MemoryItem item = new MemoryItem(id, userId, content, System.currentTimeMillis());
        store.computeIfAbsent(userId, k -> new ArrayList<>()).add(item);
        log.info("[LongTerm] 用户 {} 写入记忆 {}", userId, id);
    }

    /**
     * 根据查询检索用户相关记忆
     * @param userId 用户ID
     * @param query 查询文本
     * @param topK 返回最大条数
     * @return 匹配的记忆列表
     */
    public List<MemoryItem> recall(String userId, String query, int topK) {
        List<MemoryItem> items = store.getOrDefault(userId, List.of());
        if (items.isEmpty()) return List.of();

        // 生产环境: embed(query) 与库内向量余弦相似度 topK
        // 学习项目: 关键词粗匹配模拟
        return items.stream()
                .filter(i -> query != null && sharesKeyword(i.content(), query))
                .limit(topK)
                .toList();
    }

    private boolean sharesKeyword(String content, String query) {
        for (int i = 0; i + 1 < query.length(); i += 2) {
            String gram = query.substring(i, i + 2);
            if (content.contains(gram)) return true;
        }
        return false;
    }
}
