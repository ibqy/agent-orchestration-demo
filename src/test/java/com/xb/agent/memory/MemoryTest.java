package com.xb.agent.memory;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Memory 记忆系统测试 —— 实体记忆、记忆压缩
 *
 * 心得：实体记忆测试重点是用户隔离，压缩测试重点是阈值判断
 */
class MemoryTest {

    // ======== EntityMemory 实体记忆 ========

    @Test
    void testEntityMemorySaveAndGet() {
        EntityMemory memory = new EntityMemory();

        memory.save("user1", "orderId", "A1001");
        memory.save("user1", "customerId", "C001");
        memory.save("user2", "orderId", "A1002");

        assertEquals("A1001", memory.get("user1", "orderId"));
        assertEquals("C001", memory.get("user1", "customerId"));
        assertEquals("A1002", memory.get("user2", "orderId"));

        // 不存在的实体返回 null
        assertNull(memory.get("user1", "nonexistent"));
        assertNull(memory.get("user3", "orderId"));
    }

    @Test
    void testEntityMemoryUserIsolation() {
        EntityMemory memory = new EntityMemory();

        memory.save("user1", "orderId", "A1001");
        memory.save("user2", "orderId", "A1002");

        // 不同用户的同名实体互不干扰
        assertEquals("A1001", memory.get("user1", "orderId"));
        assertEquals("A1002", memory.get("user2", "orderId"));

        // 覆盖更新
        memory.save("user1", "orderId", "A1003");
        assertEquals("A1003", memory.get("user1", "orderId"));
        assertEquals("A1002", memory.get("user2", "orderId"));  // user2 不受影响
    }

    @Test
    void testEntityMemoryBuildPromptBlock() {
        EntityMemory memory = new EntityMemory();

        // 空记忆返回空字符串
        assertEquals("", memory.buildPromptBlock("user1"));

        // 添加 user1 的实体
        memory.save("user1", "orderId", "A1001");
        String block1 = memory.buildPromptBlock("user1");
        assertTrue(block1.contains("已知用户关键实体"));
        assertTrue(block1.contains("orderId: A1001"));

        // 添加 user2 的实体，user1 的 prompt 不应包含 user2 的实体
        memory.save("user2", "customerId", "C002");
        String block1Updated = memory.buildPromptBlock("user1");
        assertTrue(block1Updated.contains("orderId: A1001"));
        assertFalse(block1Updated.contains("customerId"));
        assertFalse(block1Updated.contains("C002"));

        // user2 的 prompt 只包含自己的实体
        String block2 = memory.buildPromptBlock("user2");
        assertTrue(block2.contains("已知用户关键实体"));
        assertTrue(block2.contains("customerId: C002"));
        assertFalse(block2.contains("orderId"));
    }

    @Test
    void testEntityMemoryBuildPromptBlockNoHeaderWithoutEntities() {
        EntityMemory memory = new EntityMemory();

        // 添加其他用户的实体，但不添加当前用户的
        memory.save("user2", "orderId", "A1002");

        // user1 没有实体，不应显示标题
        String block = memory.buildPromptBlock("user1");
        assertEquals("", block);
    }

    // ======== MemoryCompressor 记忆压缩 ========

    @Test
    void testMemoryCompressorEstimateTokens() {
        ChatModel model = mock(ChatModel.class);
        MemoryCompressor compressor = new MemoryCompressor(model);

        // 空列表
        assertEquals(0, compressor.estimateTokens(List.of()));

        // 单条消息：10 字符 → (10/2 + 1) = 6 tokens
        assertEquals(6, compressor.estimateTokens(List.of("1234567890")));

        // 多条消息累加
        List<String> messages = List.of("1234567890", "abcdefghij");  // 各 6 tokens
        assertEquals(12, compressor.estimateTokens(messages));
    }

    @Test
    void testMemoryCompressorNoCompressBelowThreshold() {
        ChatModel model = mock(ChatModel.class);
        MemoryCompressor compressor = new MemoryCompressor(model);

        // 100 tokens，maxTokens = 200，阈值 70% = 140
        // 100 < 140，不压缩
        List<String> messages = List.of("a".repeat(198));  // ~100 tokens
        List<String> result = compressor.compressIfNeeded(messages, 200);

        assertSame(messages, result);  // 返回原列表
        verify(model, never()).chat(anyString());  // 不调用 LLM
    }

    @Test
    void testMemoryCompressorCompressAboveThreshold() {
        ChatModel model = mock(ChatModel.class);
        MemoryCompressor compressor = new MemoryCompressor(model);

        // 200 tokens，maxTokens = 200，阈值 70% = 140
        // 200 > 140，需要压缩
        List<String> messages = List.of("a".repeat(398));  // ~200 tokens
        when(model.chat(anyString())).thenReturn("压缩后的摘要");

        List<String> result = compressor.compressIfNeeded(messages, 200);

        assertEquals(1, result.size());
        assertTrue(result.get(0).startsWith("[历史摘要]"));
        assertTrue(result.get(0).contains("压缩后的摘要"));
        verify(model).chat(anyString());
    }

    @Test
    void testMemoryCompressorThresholdBoundary() {
        ChatModel model = mock(ChatModel.class);
        MemoryCompressor compressor = new MemoryCompressor(model);

        // 刚好 70% 阈值：140 tokens，maxTokens = 200
        // 140 <= 140，不压缩
        List<String> messages = List.of("a".repeat(278));  // ~140 tokens
        List<String> result = compressor.compressIfNeeded(messages, 200);
        assertSame(messages, result);

        // 超过 70%：141 tokens
        // 141 > 140，压缩
        messages = List.of("a".repeat(280));  // ~141 tokens
        when(model.chat(anyString())).thenReturn("摘要");
        result = compressor.compressIfNeeded(messages, 200);
        assertEquals(1, result.size());
    }
}
