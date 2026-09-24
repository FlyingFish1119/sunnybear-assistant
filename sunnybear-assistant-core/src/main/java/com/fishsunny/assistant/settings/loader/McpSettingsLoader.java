package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.McpSettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * MCP Server 配置加载器
 * <p>
 * 在程序启动时自动读取项目下的 mcp_settings.json 文件，
 * 并将其反序列化为 {@link McpSettings} 对象装配到 Spring 容器中。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class McpSettingsLoader {

    private static final String MCP_SETTINGS_JSON = "mcp_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public McpSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析 MCP Server 配置（clients 列表），装配为 Spring Bean。
     * 文件缺失或解析失败时返回空配置 —— MCP 工具照常注册，只是没有可用的 server。
     */
    @Bean
    public McpSettings mcpSettings() {
        File settingsFile = new File(basePath + "/" + MCP_SETTINGS_JSON);

        if (!settingsFile.exists()) {
            log.warn("MCP 设置文件不存在: {}，将使用空配置", settingsFile.getAbsolutePath());
            return new McpSettings();
        }
        if (!settingsFile.isFile()) {
            log.warn("MCP 设置路径不是一个有效的文件: {}，将使用空配置", settingsFile.getAbsolutePath());
            return new McpSettings();
        }

        try {
            McpSettings settings = objectMapper.readValue(settingsFile, McpSettings.class);
            if (settings == null) {
                return new McpSettings();
            }
            log.info("MCP 设置文件加载成功: {}，已配置 {} 个 Server",
                    settingsFile.getAbsolutePath(),
                    settings.getClients() == null ? 0 : settings.getClients().size());
            return settings;
        } catch (IOException e) {
            log.error("读取 MCP 设置文件失败: {}，原因: {}，将使用空配置",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new McpSettings();
        }
    }
}
