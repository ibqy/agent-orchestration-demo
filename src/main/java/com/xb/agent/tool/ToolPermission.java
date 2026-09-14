package com.xb.agent.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具权限 —— 调用前校验用户是否拥有工具权限标签
 *
 * 心得：权限校验放在注册中心和执行器之间，
 * 而不是让每个工具自己校验 —— 漏一处就是越权事故
 */
@Component
public class ToolPermission {

    /** 用户 → 权限标签集合（生产: 从权限系统加载 + 缓存） */
    private final Map<String, Set<String>> userPermissions = new ConcurrentHashMap<>();

    public void grant(String userId, String... permissions) {
        userPermissions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .addAll(List.of(permissions));
    }

    public boolean check(String userId, ToolDescriptor descriptor) {
        Set<String> perms = userPermissions.get(userId);
        return perms != null && perms.contains(descriptor.getPermission());
    }
}
