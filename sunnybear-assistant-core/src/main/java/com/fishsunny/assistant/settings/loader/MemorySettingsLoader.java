package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.MemorySettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * 记忆设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 memory_settings.json 文件，
 * 并将其反序列化为 {@link MemorySettings} 对象装配到 Spring 容器中。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class MemorySettingsLoader {

    private static final String MEMORY_SETTINGS_JSON = "memory_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public MemorySettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析记忆设置文件，装配为 Spring Bean。
     * 记忆注入默认开启（enable=true），与旧版本“记忆总是注入”行为一致。
     */
    @Bean
    public MemorySettings memorySettings() {
        File settingsFile = new File(basePath + "/" + MEMORY_SETTINGS_JSON);

        if (!settingsFile.exists()) {
            log.warn("记忆设置文件不存在: {}，将使用默认设置（记忆注入开启）", settingsFile.getAbsolutePath());
            return new MemorySettings().setEnable(true);
        }
        if (!settingsFile.isFile()) {
            log.warn("记忆设置路径不是一个有效的文件: {}，将使用默认设置（记忆注入开启）", settingsFile.getAbsolutePath());
            return new MemorySettings().setEnable(true);
        }

        try {
            MemorySettings settings = objectMapper.readValue(settingsFile, MemorySettings.class);
            log.info("记忆设置文件加载成功: {}，内容: enable={}", settingsFile.getAbsolutePath(),
                    settings == null ? null : settings.getEnable());
            if (settings == null) {
                return new MemorySettings().setEnable(true);
            }
            return settings;
        } catch (IOException e) {
            log.error("读取记忆设置文件失败: {}，原因: {}，将使用默认设置（记忆注入开启）",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new MemorySettings().setEnable(true);
        }
    }
}
