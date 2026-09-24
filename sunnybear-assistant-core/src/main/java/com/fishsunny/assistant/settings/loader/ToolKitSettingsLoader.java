package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.ToolKitSettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * 工具集可见性设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 toolkit_settings.json 文件，
 * 并将其反序列化为 {@link ToolKitSettings} 对象装配到 Spring 容器中。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class ToolKitSettingsLoader {

    private static final String TOOLKIT_SETTINGS_JSON = "toolkit_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public ToolKitSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析工具集可见性设置文件，装配为 Spring Bean。
     * <p>
     * 文件缺失或解析失败时返回空 map —— 全部按 { ToolKit#excludeFromMainAgent()} 的代码声明生效，
     * 与引入本设置之前的行为完全一致。
     */
    @Bean
    public ToolKitSettings toolKitSettings() {
        File settingsFile = new File(basePath + "/" + TOOLKIT_SETTINGS_JSON);

        if (!settingsFile.exists()) {
            log.warn("工具集可见性设置文件不存在: {}，将使用默认设置（全部按代码声明）",
                    settingsFile.getAbsolutePath());
            return new ToolKitSettings();
        }
        if (!settingsFile.isFile()) {
            log.warn("工具集可见性设置路径不是一个有效的文件: {}，将使用默认设置（全部按代码声明）",
                    settingsFile.getAbsolutePath());
            return new ToolKitSettings();
        }

        try {
            ToolKitSettings settings = objectMapper.readValue(settingsFile, ToolKitSettings.class);
            log.info("工具集可见性设置文件加载成功: {}，内容: visibility={}",
                    settingsFile.getAbsolutePath(), settings == null ? null : settings.getVisibility());
            return settings == null ? new ToolKitSettings() : settings;
        } catch (IOException e) {
            log.error("读取工具集可见性设置文件失败: {}，原因: {}，将使用默认设置（全部按代码声明）",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new ToolKitSettings();
        }
    }
}
