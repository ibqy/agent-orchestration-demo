package com.xb.agent;

import org.mockito.Mockito;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * 内存版 Redis 桩 —— 让 Guard/SnapshotStore 在离线测试里跑真实逻辑
 *
 * <p>心得：Mockito 的 {@code when(...).thenReturn(null)} 是测试里最常见的
 * "假通过"——Guard 的并发计数、循环签名、快照读写全是 Redis 操作，
 * 全部返回 null 的话被测代码会走一堆空分支，测试绿了但啥也没验。
 * 这里用 Answer 把 opsForValue/opsForList  backing 到内存 Map/List，
 * 被测代码跑的是真逻辑。</p>
 *
 * @author ibqy
 */
public final class RedisTestFixtures {

    private RedisTestFixtures() {
    }

    /** 构造一个内存语义的 StringRedisTemplate mock */
    public static StringRedisTemplate inMemoryRedis() {
        StringRedisTemplate redis = Mockito.mock(StringRedisTemplate.class);
        Map<String, String> store = new ConcurrentHashMap<>();
        Map<String, List<String>> lists = new ConcurrentHashMap<>();

        // ===== Value 操作 =====
        ValueOperations<String, String> valueOps = Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenAnswer(inv -> store.get(inv.getArgument(0)));
        when(valueOps.increment(anyString())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long next = Long.parseLong(store.getOrDefault(key, "0")) + 1;
            store.put(key, String.valueOf(next));
            return next;
        });
        when(valueOps.increment(anyString(), anyLong())).thenAnswer(inv -> {
            String key = inv.getArgument(0);
            long delta = inv.getArgument(1);
            long next = Long.parseLong(store.getOrDefault(key, "0")) + delta;
            store.put(key, String.valueOf(next));
            return next;
        });
        doAnswer(inv -> {
            store.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(valueOps).set(anyString(), anyString(), any(Duration.class));

        // ===== List 操作 =====
        ListOperations<String, String> listOps = Mockito.mock(ListOperations.class);
        when(redis.opsForList()).thenReturn(listOps);
        when(listOps.range(anyString(), anyLong(), anyLong())).thenAnswer(inv -> {
            List<String> list = lists.get(inv.getArgument(0));
            return list == null ? List.of() : new ArrayList<>(list);
        });
        when(listOps.rightPush(anyString(), anyString())).thenAnswer(inv -> {
            lists.computeIfAbsent(inv.getArgument(0), k -> new ArrayList<>())
                    .add(inv.getArgument(1));
            return 1L;
        });
        doAnswer(inv -> {
            List<String> list = lists.get(inv.getArgument(0));
            if (list != null) {
                long start = inv.getArgument(1);
                long end = inv.getArgument(2);
                int size = list.size();
                int from = (int) (start < 0 ? Math.max(0, size + start) : start);
                int to = (int) (end < 0 ? size + end : Math.min(size - 1, end));
                if (from <= to && from < size) {
                    lists.put(inv.getArgument(0), new ArrayList<>(list.subList(from, to + 1)));
                }
            }
            return null;
        }).when(listOps).trim(anyString(), anyLong(), anyLong());

        // ===== 通用 =====
        when(redis.expire(anyString(), any(Duration.class))).thenReturn(true);
        when(redis.delete(anyString())).thenReturn(true);

        return redis;
    }
}
