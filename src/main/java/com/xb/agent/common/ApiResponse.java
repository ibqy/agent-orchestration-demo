package com.xb.agent.common;

/**
 * 统一 API 响应封装
 *
 * <p>作者：xb | 日期：2026-09-17</p>
 *
 * <p><b>教学知识点</b>：REST 接口统一返回格式，让前端/调用方可以用固定结构解析响应。
 * 使用 Java 21 record 实现不可变数据载体。</p>
 *
 * @author ibqy
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
