package com.fishsunny.assistant.utils;

/*
 * @Usage 从模型输出文本中提取 JSON 片段的通用工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/31
 */

public final class JsonUtils {

    private JsonUtils() {
    }

    /**
     * 从一段可能夹带说明文字或 ```json 代码围栏的文本里，提取第一个完整的 JSON 对象。
     * <p>
     * 按括号配平扫描：扫描过程跳过字符串内部与转义字符，所以 JSON 字符串里出现的 { } 不会被误判；
     * 遇到第一个深度归零的 } 就直接返回，后面多余的说明文字一概丢掉。
     * 找不到完整对象时返回空串，调用方用 StringUtils.hasText 判断即可。
     */
    public static String extractJsonObject(String raw) {
        if (raw == null) {
            return "";
        }
        int start = raw.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return raw.substring(start, i + 1);
                }
            }
        }
        return "";
    }
}
