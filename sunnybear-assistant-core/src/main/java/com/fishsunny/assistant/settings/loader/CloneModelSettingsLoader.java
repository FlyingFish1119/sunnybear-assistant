package com.fishsunny.assistant.settings.loader;

/*
 * @Usage 执行体模型列表加载器
 *
 *        启动时读取 clone_model_settings.json 并装配为 Bean，同时把五个内置场景模型
 *        （chat / chat_pro / operator / mission / cub，排除 task）的 Bean 引用注入进去——
 *        持有的是运行时热改的 Bean 本体，所以执行体列表里的内置条目能实时跟随场景设置。
 *        json 文件不存在 / 损坏时给空追加列表而不是启动失败：内置条目仍然可用。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/19
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.CloneModelSettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;

@Configuration
@Slf4j
public class CloneModelSettingsLoader {

    private static final String CLONE_MODEL_SETTINGS_JSON = "clone_model_settings.json";

    @Value("${assistant.settings.base-path:data/settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public CloneModelSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Bean
    public CloneModelSettings cloneModelSettings(
            @Qualifier(AISettings.CHAT) AISettings chatAISettings,
            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
            @Qualifier(AISettings.OPERATOR) AISettings operatorAISettings,
            @Qualifier(AISettings.MISSION) AISettings missionAISettings,
            @Qualifier(AISettings.CUB) AISettings cubAISettings) {
        CloneModelSettings settings = readSettingsFile();
        LinkedHashMap<String, AISettings> builtinScenes = new LinkedHashMap<>();
        builtinScenes.put(AISettings.CHAT, chatAISettings);
        builtinScenes.put(AISettings.CHAT_PRO, chatProAISettings);
        builtinScenes.put(AISettings.OPERATOR, operatorAISettings);
        builtinScenes.put(AISettings.MISSION, missionAISettings);
        builtinScenes.put(AISettings.CUB, cubAISettings);
        settings.setBuiltinScenes(builtinScenes);
        log.info("执行体模型列表装配完成: 追加 {} 条，内置注入 5 个场景模型（chat/chat_pro/operator/mission/cub）",
                settings.getModels() == null ? 0 : settings.getModels().size());
        return settings;
    }

    /** 读 json 追加部分；文件不存在 / 损坏时给空列表，让内置条目照常可用 */
    private CloneModelSettings readSettingsFile() {
        File settingsFile = new File(basePath + "/" + CLONE_MODEL_SETTINGS_JSON);
        if (!settingsFile.exists() || !settingsFile.isFile()) {
            log.warn("执行体模型列表文件不存在: {}，追加部分为空（内置场景条目仍可用）", settingsFile.getAbsolutePath());
            return new CloneModelSettings();
        }
        try {
            CloneModelSettings settings = objectMapper.readValue(settingsFile, CloneModelSettings.class);
            log.info("执行体模型列表加载成功: {}，追加 {} 条", settingsFile.getAbsolutePath(),
                    settings == null || settings.getModels() == null ? 0 : settings.getModels().size());
            return settings == null ? new CloneModelSettings() : settings;
        } catch (IOException e) {
            log.error("执行体模型列表文件读取失败: {}，原因: {}，追加部分为空（内置场景条目仍可用）",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new CloneModelSettings();
        }
    }
}
