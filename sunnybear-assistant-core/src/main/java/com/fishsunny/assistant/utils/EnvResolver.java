package com.fishsunny.assistant.utils;

/*
 * @Usage 解析配置中的 "env:XXX" 语法，从系统环境变量中读取敏感信息（如 API Key）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/7
 */

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class EnvResolver {

    private static final Logger log = LoggerFactory.getLogger(EnvResolver.class);

    /**
     * 环境变量前缀
     */
    private static final String ENV_PREFIX = "env:";

    /**
     * 解析配置字符串中可能存在的 "env:XXX" 前缀：
     * 设置文件中写成 "env:METASO_API_KEY"，调用处解析时自动从系统环境变量读取；
     * 若环境变量不存在或为空，替换为空串并打印 warn 日志（由调用方结合 hasText 自行兜底）。
     * <p>
     * 非 env: 前缀的字符串（含明文 key）原样返回；null 输入原样返回。
     *
     * @param value 原始配置值，可为明文或 "env:KEY" 形式
     * @return 解析后的实际值
     */
    public static String resolve(String value) {
        if (value == null) {
            return null;
        }
        if (!value.startsWith(ENV_PREFIX)) {
            return value;
        }
        String envKey = value.substring(ENV_PREFIX.length()).trim();
        String resolved = System.getenv(envKey);
        if (resolved != null && !resolved.isEmpty()) {
            return resolved;
        }
        log.warn("环境变量不存在或为空: env:{}，已替换为空串", envKey);
        return "";
    }
}
