package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.AssistantSettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * 助手设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 assistant_settings.json 文件，
 * 并将其反序列化为 {@link AssistantSettings} 对象装配到 Spring 容器中。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class AssistantSettingsLoader {

    private static final String ASSISTANT_SETTINGS_JSON = "assistant_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public AssistantSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public AssistantSettings assistantSettings() {
        File settingsFile = new File(basePath + "/" + ASSISTANT_SETTINGS_JSON);

        if (!settingsFile.exists()) {
            log.warn("助手设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
            return new AssistantSettings();
        }

        if (!settingsFile.isFile()) {
            log.warn("助手设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
            return new AssistantSettings();
        }

        try {
            AssistantSettings settings = objectMapper.readValue(settingsFile, AssistantSettings.class);
            log.info("助手设置文件加载成功: {}，内容: assistant_name={}, avatar={}",
                    settingsFile.getAbsolutePath(),
                    settings.getAssistantName(),
                    settings.getAvatar());
            return settings;

        } catch (IOException e) {
            log.error("读取助手设置文件失败: {}，原因: {}，将使用默认设置",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new AssistantSettings();
        }
    }
}
