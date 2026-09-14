package com.xb.agent.reasoning;

/**
 * LLM 输出解析 —— 从自由文本中提取 ACTION/ANSWER
 *
 * 心得：别指望 LLM 永远按格式输出，
 * 解析器必须容错：找不到 ACTION 关键字时整段当作 THOUGHT
 */
public final class ActionParser {

    private ActionParser() {}

    public static AgentAction parse(String llmOutput) {
        String thought = extractAfter(llmOutput, "THOUGHT:", "ACTION:");
        String actionJson = extractJsonAfter(llmOutput, "ACTION:");
        String answer = extractAfter(llmOutput, "ANSWER:", null);

        if (answer != null) {
            return AgentAction.ofAnswer(thought, answer.trim());
        }
        if (actionJson != null) {
            String tool = extractField(actionJson, "tool");
            String args = extractField(actionJson, "args");
            if (tool != null) {
                return AgentAction.ofTool(thought, tool, args == null ? "{}" : args);
            }
        }
        return AgentAction.ofRetry(thought);
    }

    /** 提取 from 关键字到 to 关键字之间的文本 */
    private static String extractAfter(String text, String from, String to) {
        int i = text.indexOf(from);
        if (i < 0) return text.trim();
        int begin = i + from.length();
        int end = to != null ? text.indexOf(to, begin) : -1;
        return (end > begin ? text.substring(begin, end) : text.substring(begin)).trim();
    }

    private static String extractJsonAfter(String text, String keyword) {
        int i = text.indexOf(keyword);
        if (i < 0) return null;
        int brace = text.indexOf('{', i);
        if (brace < 0) return null;
        // 匹配第一个完整 JSON 对象
        int depth = 0;
        for (int j = brace; j < text.length(); j++) {
            if (text.charAt(j) == '{') depth++;
            if (text.charAt(j) == '}') {
                depth--;
                if (depth == 0) return text.substring(brace, j + 1);
            }
        }
        return null;
    }

    /** 不引 Jackson，简易提取字符串字段（含嵌套对象的粗提取） */
    static String extractField(String json, String field) {
        String key = "\"" + field + "\"";
        int i = json.indexOf(key);
        if (i < 0) return null;
        int colon = json.indexOf(':', i + key.length());
        if (colon < 0) return null;
        int p = colon + 1;
        while (p < json.length() && Character.isWhitespace(json.charAt(p))) p++;
        if (p >= json.length()) return null;
        char c = json.charAt(p);
        if (c == '"') {
            int end = json.indexOf('"', p + 1);
            return json.substring(p + 1, end);
        }
        if (c == '{') {
            int depth = 0;
            for (int j = p; j < json.length(); j++) {
                if (json.charAt(j) == '{') depth++;
                if (json.charAt(j) == '}') {
                    depth--;
                    if (depth == 0) return json.substring(p, j + 1);
                }
            }
        }
        return null;
    }
}
