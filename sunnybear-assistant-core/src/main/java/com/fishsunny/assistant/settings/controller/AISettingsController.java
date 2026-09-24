package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import com.fishsunny.assistant.settings.AISettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型设置控制器
 * <p>
 * 提供各用途模型（chat / chat_pro / ocr / mission / task / cub）的查询与保存接口，
 * 以及适配器与可选模型列表接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class AISettingsController {

    private static final Logger log = LoggerFactory.getLogger(AISettingsController.class);

    private final ObjectMapper objectMapper;
    private final AIAdapterFactory adapterFactory;
    private final String aiSettingsPath;
    private final Map<String, AISettings> aiSettingsMap;

    public AISettingsController(
            ObjectMapper objectMapper,
            AIAdapterFactory adapterFactory,
            @Value("${ai-settings.path:settings/ai_settings.json}") String aiSettingsPath,
            @Qualifier(AISettings.CHAT) AISettings chatAISettings,
            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
            @Qualifier(AISettings.OCR) AISettings ocrAISettings,
            @Qualifier(AISettings.MISSION) AISettings missionAISettings,
            @Qualifier(AISettings.TASK) AISettings taskAISettings,
            @Qualifier(AISettings.CUB) AISettings cubAISettings) {
        this.objectMapper = objectMapper;
        this.adapterFactory = adapterFactory;
        this.aiSettingsPath = aiSettingsPath;
        this.aiSettingsMap = new LinkedHashMap<>();
        this.aiSettingsMap.put(AISettings.CHAT, chatAISettings);
        this.aiSettingsMap.put(AISettings.CHAT_PRO, chatProAISettings);
        this.aiSettingsMap.put(AISettings.OCR, ocrAISettings);
        this.aiSettingsMap.put(AISettings.MISSION, missionAISettings);
        this.aiSettingsMap.put(AISettings.TASK, taskAISettings);
        this.aiSettingsMap.put(AISettings.CUB, cubAISettings);
    }

    @RequestMapping("/chat/get")
    public RestResponse getChatAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.CHAT));
    }

    @RequestMapping("/chat_pro/get")
    public RestResponse getChatProAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.CHAT_PRO));
    }

    @RequestMapping("/ocr/get")
    public RestResponse getOcrAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.OCR));
    }

    @RequestMapping("/mission/get")
    public RestResponse getMissionAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.MISSION));
    }

    @RequestMapping("/task/get")
    public RestResponse getTaskAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.TASK));
    }

    @RequestMapping("/cub/get")
    public RestResponse getCubAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.CUB));
    }

    /**
     * 获取所有已注册的适配器名称列表
     */
    @RequestMapping("/adapters/list")
    public RestResponse getAdapterList() {
        return new RestResponse().success(adapterFactory.getAvailableAdapterNames());
    }

    /**
     * 获取指定适配器的可选模型列表（供设置页下拉框使用），每个条目附带能拿到的简要信息（描述、上下文长度等）。
     * 适配器未配置 modelUrl 或拉取失败时返回错误，前端回退为手动输入。
     */
    @RequestMapping("/adapters/models")
    public RestResponse getAdapterModels(@RequestParam(value = "apiName", required = false) String apiName) {
        if (!StringUtils.hasText(apiName)) {
            return new RestResponse().error("apiName 不能为空");
        }
        try {
            return new RestResponse().success(adapterFactory.listModels(apiName));
        } catch (Exception e) {
            log.warn("获取适配器模型列表失败: apiName={}, message={}", apiName, e.getMessage());
            return new RestResponse().error(e.getMessage());
        }
    }

    private boolean validateAISettings(AISettings settings) {
        if (settings == null) {
            return false;
        }
        if (!StringUtils.hasText(settings.getModel())) {
            return false;
        }
        if (!StringUtils.hasText(settings.getAdapterName())) {
            return false;
        }
        if (settings.getStream() == null) {
            settings.setStream(false);
        }
        return true;
    }

    @PostMapping("/chat/save")
    public RestResponse saveChatAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings chatAISettings = aiSettingsMap.get(AISettings.CHAT);
        chatAISettings.copy(settings);
        aiSettingsMap.put(AISettings.CHAT, chatAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI chat 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/chat_pro/save")
    public RestResponse saveChatProAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings chatProAISettings = aiSettingsMap.get(AISettings.CHAT_PRO);
        chatProAISettings.copy(settings);
        aiSettingsMap.put(AISettings.CHAT_PRO, chatProAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI chat_pro 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/ocr/save")
    public RestResponse saveOcrAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings ocrAISettings = aiSettingsMap.get(AISettings.OCR);
        ocrAISettings.copy(settings);
        aiSettingsMap.put(AISettings.OCR, ocrAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI ocr 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/mission/save")
    public RestResponse saveMissionAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings missionAISettings = aiSettingsMap.get(AISettings.MISSION);
        missionAISettings.copy(settings);
        aiSettingsMap.put(AISettings.MISSION, missionAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI mission 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/task/save")
    public RestResponse saveTaskAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings taskAISettings = aiSettingsMap.get(AISettings.TASK);
        taskAISettings.copy(settings);
        aiSettingsMap.put(AISettings.TASK, taskAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI task 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @PostMapping("/cub/save")
    public RestResponse saveCubAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings cubAISettings = aiSettingsMap.get(AISettings.CUB);
        cubAISettings.copy(settings);
        aiSettingsMap.put(AISettings.CUB, cubAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI cub 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
}
