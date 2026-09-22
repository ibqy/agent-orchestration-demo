package com.xb.agent.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 工具参数解析 —— 统一 Jackson 入口
 *
 * 心得：LLM 给的 argsJson 千奇百怪（多空格/换行/嵌套字符串），
 * 解析统一收口在一个类，别散落在每个工具里
 *
 * @author ibqy
 */
public final class ToolArgs {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolArgs() {}

    /**
     * 从 JSON 中提取字符串参数，缺失或解析失败返回 null
     * @param argsJson LLM 输出的 JSON 参数字符串
     * @param field 要提取的字段名
     * @return 字段值，不存在或解析异常返回 null
     */
    public static String extract(String argsJson, String field) {
        try {
            JsonNode node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            JsonNode value = node.get(field);
            return value != null && !value.isNull() ? value.asText() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 从 JSON 中提取整数参数，缺失或非数字返回 null
     * @param argsJson LLM 输出的 JSON 参数字符串
     * @param field 要提取的字段名
     * @return 字段的整数值，不存在或解析异常返回 null
     */
    public static Integer extractInt(String argsJson, String field) {
        try {
            JsonNode node = MAPPER.readTree(argsJson == null || argsJson.isBlank() ? "{}" : argsJson);
            JsonNode value = node.get(field);
            return value != null && value.isNumber() ? value.asInt() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
