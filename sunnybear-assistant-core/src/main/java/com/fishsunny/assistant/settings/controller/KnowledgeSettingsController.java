package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.settings.KnowledgeSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;

/**
 * 知识库设置控制器
 * <p>
 * 提供知识库参数（注入开关与置信度阈值）的查询与保存接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class KnowledgeSettingsController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSettingsController.class);

    private final ObjectMapper objectMapper;
    private final String knowledgeSettingsPath;
    private final KnowledgeSettings knowledgeSettings;

    public KnowledgeSettingsController(
            ObjectMapper objectMapper,
            @Value("${knowledge-settings.path:settings/knowledge_settings.json}") String knowledgeSettingsPath,
            KnowledgeSettings knowledgeSettings) {
        this.objectMapper = objectMapper;
        this.knowledgeSettingsPath = knowledgeSettingsPath;
        this.knowledgeSettings = knowledgeSettings;
    }

    @RequestMapping("/knowledgesettings/get")
    public RestResponse getKnowledgeSettings() {
        return new RestResponse().success(knowledgeSettings);
    }

    @PostMapping("/knowledge/save")
    public RestResponse saveKnowledgeSettings(@RequestBody(required = false) KnowledgeSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        knowledgeSettings.setEnable(Boolean.TRUE.equals(settings.getEnable()));
        knowledgeSettings.setConfidenceThreshold(settings.getConfidenceThreshold());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(knowledgeSettingsPath), knowledgeSettings);
        } catch (Exception e) {
            log.error("保存知识库设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
}
