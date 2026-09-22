package com.xb.agent.guard;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 循环熔断 —— 防止 Agent 无限循环调工具
 *
 * 心得：只数"总调用次数"不够，还要检测"重复参数"——
 * 同一工具+同一参数连续出现 3 次基本就是死循环，
 * 让 LLM 换参数比重试更有效
 *
 * @author ibqy
 */
@Component
public class LoopBreaker {

    private static final String CALL_KEY = "guard:calls:%s";
    private static final String SIG_KEY = "guard:sig:%s";
    private static final Duration WINDOW = Duration.ofMinutes(30);

    private final StringRedisTemplate redis;

    public LoopBreaker(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 检查工具调用是否触发熔断（次数超限或死循环）
     * @param taskId 任务ID
     * @param toolName 工具名
     * @param argsJson 参数 JSON
     * @param maxCalls 最大调用次数
     * @return 触发熔断返回错误提示，否则返回 null
     */
    public String check(String taskId, String toolName, String argsJson, int maxCalls) {
        String callKey = CALL_KEY.formatted(taskId);
        Long calls = redis.opsForValue().increment(callKey);
        redis.expire(callKey, WINDOW);

        if (calls != null && calls > maxCalls) {
            return "[GUARD] 已达最大工具调用次数 " + maxCalls + "，任务强制终止。建议总结现有信息直接回答";
        }

        // 重复签名检测: toolName + 参数指纹（签名统一存 List，避免与 String 类型冲突）
        String signature = toolName + ":" + (argsJson == null ? "" : argsJson.hashCode());
        String sigKey = SIG_KEY.formatted(taskId);
        List<String> recent = redis.opsForList().range(sigKey, 0, -1);

        // 最近签名全相同 = 死循环（list 已有2次 + 当前 = 连续3次）
        if (recent != null && recent.size() >= 2 && recent.stream().allMatch(signature::equals)) {
            return "[GUARD] 检测到重复调用 " + toolName + " 相同参数连续 3 次，疑似死循环。请更换参数或方案";
        }

        redis.opsForList().rightPush(sigKey, signature);
        redis.opsForList().trim(sigKey, -3, -1);  // 只留最近3次签名
        redis.expire(sigKey, WINDOW);
        return null;
    }
}
