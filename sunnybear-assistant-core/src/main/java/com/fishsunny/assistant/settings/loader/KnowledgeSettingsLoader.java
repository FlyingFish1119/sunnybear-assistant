package com.fishsunny.assistant.settings.loader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.settings.KnowledgeSettings;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;

/**
 * 知识库设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 knowledge_settings.json 文件，
 * 并将其反序列化为 {@link KnowledgeSettings} 对象装配到 Spring 容器中。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
@Slf4j
public class KnowledgeSettingsLoader {

    private static final String KNOWLEDGE_SETTINGS_JSON = "knowledge_settings.json";

    @Value("${assistant.settings.base-path:settings/}")
    private String basePath;

    private final ObjectMapper objectMapper;

    public KnowledgeSettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 读取并解析知识库设置文件（扁平结构，含 enable 开关与置信度阈值），装配为 Spring Bean。
     * 知识库注入默认关闭（enable=false），阈值默认 0.25，与旧版本文件缺失时的语义一致。
     */
    @Bean
    public KnowledgeSettings knowledgeSettings() {
        File settingsFile = new File(basePath + "/" + KNOWLEDGE_SETTINGS_JSON);

        if (!settingsFile.exists()) {
            log.warn("知识库设置文件不存在: {}，将使用默认设置（注入关闭）", settingsFile.getAbsolutePath());
            return new KnowledgeSettings().setEnable(false);
        }
        if (!settingsFile.isFile()) {
            log.warn("知识库设置路径不是一个有效的文件: {}，将使用默认设置（注入关闭）", settingsFile.getAbsolutePath());
            return new KnowledgeSettings().setEnable(false);
        }

        try {
            KnowledgeSettings settings = objectMapper.readValue(settingsFile, KnowledgeSettings.class);
            log.info("知识库设置文件加载成功: {}，内容: enable={}, confidenceThreshold={}", settingsFile.getAbsolutePath(),
                    settings == null ? null : settings.getEnable(),
                    settings == null ? null : settings.getConfidenceThreshold());
            if (settings == null) {
                return new KnowledgeSettings().setEnable(false);
            }
            return settings;
        } catch (IOException e) {
            log.error("读取知识库设置文件失败: {}，原因: {}，将使用默认设置（注入关闭）",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new KnowledgeSettings().setEnable(false);
        }
    }
}
