package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.AISettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.Map;

/**
 * AI 模型设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 ai_settings.json 文件，
 * 并按模型用途（chat / chat_pro / ocr / mission / task / cub）分别装配为 Spring Bean。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class AISettingsLoader {

    private static final String AI_SETTINGS_JSON = "ai_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public AISettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    private Map<String, AISettings> aiSettingsCache = null;

    @Bean(AISettings.CHAT)
    public AISettings chatAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.CHAT, new AISettings());
    }

    @Bean(AISettings.CHAT_PRO)
    public AISettings chatProAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.CHAT_PRO, new AISettings());
    }

    @Bean(AISettings.OCR)
    public AISettings ocrAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.OCR, new AISettings());
    }

    @Bean(AISettings.MISSION)
    public AISettings missionAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.MISSION, new AISettings());
    }

    @Bean(AISettings.TASK)
    public AISettings taskAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.TASK, new AISettings());
    }

    @Bean(AISettings.CUB)
    public AISettings cubAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.CUB, new AISettings());
    }

    private void initAISettingsFile() {
        synchronized (this) {
            if (aiSettingsCache != null) {
                return;
            }
            File settingsFile = new File(basePath + "/" + AI_SETTINGS_JSON);
            if (!settingsFile.exists()) {
                log.warn("AI设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
                aiSettingsCache = loadDefaultAISettings();
                return;
            }
            if (!settingsFile.isFile()) {
                log.warn("AI设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
                aiSettingsCache = loadDefaultAISettings();
                return;
            }
            try {
                JavaType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, AISettings.class);
                aiSettingsCache = objectMapper.readValue(settingsFile, mapType);
            } catch (IOException e) {
                log.warn("AI设置文件读取失败: {}，将使用默认设置", settingsFile.getAbsolutePath());
                aiSettingsCache = loadDefaultAISettings();
                return;
            }
        }
    }

    private Map<String, AISettings> loadDefaultAISettings() {
        return Map.of(
                AISettings.CHAT, new AISettings().setModel("deepseek-v4-flash")
                        .setStream(true)
                        .setThinking(true),
                AISettings.CHAT_PRO, new AISettings().setModel("deepseek-v4-pro")
                        .setStream(true)
                        .setThinking(true),
                AISettings.OCR, new AISettings().setModel("kimi-k2.6")
                        .setStream(false)
                        .setThinking(false),
                AISettings.MISSION, new AISettings().setModel("deepseek-v4-flash")
                        .setStream(false)
                        .setThinking(false),
                AISettings.TASK, new AISettings().setModel("deepseek-v4-pro")
                        .setStream(false)
                        .setThinking(true),
                AISettings.CUB, new AISettings().setModel("deepseek-v4-flash")
                        .setStream(false)
                        .setThinking(false)
        );
    }
}
