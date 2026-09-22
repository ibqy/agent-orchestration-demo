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
 *
 * @author ibqy
 */
@Component
public class ToolPermission {

    /** 用户 → 权限标签集合（生产: 从权限系统加载 + 缓存） */
    private final Map<String, Set<String>> userPermissions = new ConcurrentHashMap<>();

    /**
     * 为用户授予权限标签
     * @param userId 用户ID
     * @param permissions 要授予的权限标签（可多个）
     */
    public void grant(String userId, String... permissions) {
        userPermissions.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet())
                .addAll(List.of(permissions));
    }

    /**
     * 校验用户是否拥有调用该工具所需的权限
     * @param userId 用户ID
     * @param descriptor 工具描述符（含所需权限标签）
     * @return true 表示用户拥有该工具所需权限
     */
    public boolean check(String userId, ToolDescriptor descriptor) {
        Set<String> perms = userPermissions.get(userId);
        return perms != null && perms.contains(descriptor.getPermission());
    }
}
