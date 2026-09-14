package com.xb.agent.memory;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 分层记忆门面 —— 串联三层记忆
 *
 * 心得1: 检索顺序 = 实体（精确）→ 长时（向量）→ 短时（最近对话），
 * 三层结果合并注入 SystemPrompt
 * 心得2: 三种记忆职责严格分开:
 *   ChatMemory/短时 = 本次对话上下文
 *   任务快照 = 任务执行进度
 *   长时记忆 = 用户长期知识库
 */
@Service
public class LayeredMemoryService {

    private static final Logger log = LoggerFactory.getLogger(LayeredMemoryService.class);

    private final ShortTermMemory shortTerm;
    private final LongTermMemory longTerm;
    private final EntityMemory entityMemory;
    private final MemoryCompressor compressor;

    public LayeredMemoryService(ShortTermMemory shortTerm, LongTermMemory longTerm,
                                EntityMemory entityMemory, MemoryCompressor compressor) {
        this.shortTerm = shortTerm;
        this.longTerm = longTerm;
        this.entityMemory = entityMemory;
        this.compressor = compressor;
    }

    /** 任务启动时构建记忆上下文 */
    public String buildContext(String sessionId, String userId, String goal) {
        StringBuilder ctx = new StringBuilder();

        String entityBlock = entityMemory.buildPromptBlock(userId);
        if (!entityBlock.isEmpty()) ctx.append(entityBlock);

        List<LongTermMemory.MemoryItem> recalled = longTerm.recall(userId, goal, 5);
        if (!recalled.isEmpty()) {
            ctx.append("用户历史相关信息:\n");
            recalled.forEach(i -> ctx.append("- ").append(i.content()).append('\n'));
        }

        List<String> recent = compressor.compressIfNeeded(shortTerm.recent(sessionId, 20), 8000);
        if (!recent.isEmpty()) {
            ctx.append("最近对话:\n");
            recent.forEach(r -> ctx.append("- ").append(r).append('\n'));
        }

        log.info("[Memory] 上下文构建完成: 实体={} 长时={} 短时={}",
                entityBlock.isEmpty() ? 0 : 1, recalled.size(), recent.size());
        return ctx.toString();
    }

    /** 任务结束时抽取重要信息写长时记忆 */
    public void onTaskFinished(String sessionId, String userId, String goal, String answer) {
        if (answer != null && answer.length() > 50) {
            longTerm.remember(userId, "任务[" + goal + "] 结论: " + abbreviate(answer));
        }
        shortTerm.append(sessionId, "user", goal);
        shortTerm.append(sessionId, "assistant", abbreviate(answer));
    }

    private String abbreviate(String s) {
        return s != null && s.length() > 200 ? s.substring(0, 200) : s;
    }
}
