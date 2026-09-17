package com.xb.agent.guard;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ListOperations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/**
 * Guard 防护机制测试 —— 并发限制、循环熔断、Token 预算
 *
 * 心得：Guard 是生产环境的保险丝，测试必须覆盖边界条件
 */
class GuardTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private ListOperations<String, String> listOps;

    @BeforeEach
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        listOps = mock(ListOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(redis.opsForList()).thenReturn(listOps);
    }

    // ======== ConcurrencyLimiter 并发限制 ========

    @Test
    void testConcurrencyAcquireAndRelease() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(redis);
        String userId = "user1";
        String key = "guard:conc:" + userId;

        // 第一次获取，计数器 = 1
        when(valueOps.increment(key)).thenReturn(1L);
        assertTrue(limiter.tryAcquire(userId, 3));

        // 第二次获取，计数器 = 2
        when(valueOps.increment(key)).thenReturn(2L);
        assertTrue(limiter.tryAcquire(userId, 3));

        // 第三次获取，计数器 = 3
        when(valueOps.increment(key)).thenReturn(3L);
        assertTrue(limiter.tryAcquire(userId, 3));

        // 第四次获取，计数器 = 4，超过限制
        when(valueOps.increment(key)).thenReturn(4L);
        assertFalse(limiter.tryAcquire(userId, 3));

        // 释放后计数器减 1
        when(valueOps.get(key)).thenReturn("4");
        limiter.release(userId);
        verify(valueOps).decrement(key);
    }

    @Test
    void testConcurrencyReleasePreventNegative() {
        ConcurrencyLimiter limiter = new ConcurrencyLimiter(redis);
        String userId = "user2";
        String key = "guard:conc:" + userId;

        // 计数器为 0 时释放，不应 decrement
        when(valueOps.get(key)).thenReturn("0");
        limiter.release(userId);
        verify(valueOps, never()).decrement(key);

        // 计数器为 1 时释放，应该 decrement
        when(valueOps.get(key)).thenReturn("1");
        limiter.release(userId);
        verify(valueOps).decrement(key);

        // 计数器格式错误时，删除 key
        when(valueOps.get(key)).thenReturn("invalid");
        limiter.release(userId);
        verify(redis).delete(key);
    }

    // ======== LoopBreaker 循环熔断 ========

    @Test
    void testLoopBreakerMaxCallsExceeded() {
        LoopBreaker breaker = new LoopBreaker(redis);
        String taskId = "task1";
        String callKey = "guard:calls:" + taskId;

        // 前 5 次调用正常
        for (int i = 1; i <= 5; i++) {
            when(valueOps.increment(callKey)).thenReturn((long) i);
            when(listOps.range(anyString(), anyLong(), anyLong())).thenReturn(List.of());
            assertNull(breaker.check(taskId, "tool", "{}", 5));
        }

        // 第 6 次超限
        when(valueOps.increment(callKey)).thenReturn(6L);
        String error = breaker.check(taskId, "tool", "{}", 5);
        assertNotNull(error);
        assertTrue(error.contains("最大工具调用次数"));
    }

    @Test
    void testLoopBreakerDetectsRepeatedSignature() {
        LoopBreaker breaker = new LoopBreaker(redis);
        String taskId = "task2";
        String callKey = "guard:calls:" + taskId;
        String sigKey = "guard:sig:" + taskId;

        when(valueOps.increment(callKey)).thenReturn(1L, 2L, 3L);

        // 使用 LinkedList 模拟 Redis List 的状态
        java.util.Deque<String> signatureList = new java.util.LinkedList<>();

        // range 返回当前 list 的内容
        when(listOps.range(sigKey, 0, -1)).thenAnswer(inv -> new java.util.ArrayList<>(signatureList));

        // rightPush 添加元素到 list
        doAnswer(inv -> {
            String sig = inv.getArgument(1);
            signatureList.addLast(sig);
            return null;
        }).when(listOps).rightPush(eq(sigKey), anyString());

        // trim 保留最后 3 个元素
        doAnswer(inv -> {
            while (signatureList.size() > 3) {
                signatureList.removeFirst();
            }
            return null;
        }).when(listOps).trim(eq(sigKey), anyLong(), anyLong());

        // 第 1 次调用：list 为空，不检测
        assertNull(breaker.check(taskId, "tool", "{\"id\":\"1\"}", 10));
        assertEquals(1, signatureList.size());

        // 第 2 次调用：list = [sig1]，size < 2，不检测
        assertNull(breaker.check(taskId, "tool", "{\"id\":\"1\"}", 10));
        assertEquals(2, signatureList.size());

        // 第 3 次调用：list = [sig1, sig1]，size = 2 且全相同，检测到死循环
        String error = breaker.check(taskId, "tool", "{\"id\":\"1\"}", 10);
        assertNotNull(error);
        assertTrue(error.contains("重复调用"));
        assertTrue(error.contains("死循环"));
    }

    // ======== TokenBudget Token 预算 ========

    @Test
    void testTokenBudgetEstimate() {
        TokenBudget budget = new TokenBudget(redis);

        assertEquals(0, budget.estimate(null));
        assertEquals(0, budget.estimate(""));
        assertEquals(5, budget.estimate("1234567890"));  // 10 chars / 2 = 5
        assertEquals(50, budget.estimate("a".repeat(100)));  // 100 chars / 2 = 50
    }

    @Test
    void testTokenBudgetCheck() {
        TokenBudget budget = new TokenBudget(redis);
        String taskId = "task3";
        String key = "guard:tokens:" + taskId;

        // 未超预算
        when(valueOps.get(key)).thenReturn("500");
        assertNull(budget.checkBudget(taskId, 1000));

        // 刚好达到预算
        when(valueOps.get(key)).thenReturn("1000");
        String error = budget.checkBudget(taskId, 1000);
        assertNotNull(error);
        assertTrue(error.contains("token 预算耗尽"));

        // 超过预算
        when(valueOps.get(key)).thenReturn("1500");
        error = budget.checkBudget(taskId, 1000);
        assertNotNull(error);
    }

    @Test
    void testTokenBudgetAdd() {
        TokenBudget budget = new TokenBudget(redis);
        String taskId = "task4";
        String key = "guard:tokens:" + taskId;

        budget.add(taskId, 100);
        verify(valueOps).increment(key, 100);

        budget.add(taskId, 200);
        verify(valueOps).increment(key, 200);
    }
}
