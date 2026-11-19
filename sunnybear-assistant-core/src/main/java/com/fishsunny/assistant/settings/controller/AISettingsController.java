package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.CloneModelSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模型设置控制器
 * <p>
 * 提供各用途模型（chat / chat_pro / operator / mission / task / cub）的查询与保存接口，
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
    private final CloneModelSettings cloneModelSettings;
    private final String cloneModelSettingsPath;

    public AISettingsController(
            ObjectMapper objectMapper,
            AIAdapterFactory adapterFactory,
            @Value("${ai-settings.path:data/settings/ai_settings.json}") String aiSettingsPath,
            @Value("${clone-model-settings.path:data/settings/clone_model_settings.json}") String cloneModelSettingsPath,
            CloneModelSettings cloneModelSettings,
            @Qualifier(AISettings.CHAT) AISettings chatAISettings,
            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
            @Qualifier(AISettings.OPERATOR) AISettings operatorAISettings,
            @Qualifier(AISettings.MISSION) AISettings missionAISettings,
            @Qualifier(AISettings.TASK) AISettings taskAISettings,
            @Qualifier(AISettings.CUB) AISettings cubAISettings) {
        this.objectMapper = objectMapper;
        this.adapterFactory = adapterFactory;
        this.aiSettingsPath = aiSettingsPath;
        this.cloneModelSettingsPath = cloneModelSettingsPath;
        this.cloneModelSettings = cloneModelSettings;
        this.aiSettingsMap = new LinkedHashMap<>();
        this.aiSettingsMap.put(AISettings.CHAT, chatAISettings);
        this.aiSettingsMap.put(AISettings.CHAT_PRO, chatProAISettings);
        this.aiSettingsMap.put(AISettings.OPERATOR, operatorAISettings);
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

    @RequestMapping("/operator/get")
    public RestResponse getOperatorAISettings() {
        return new RestResponse().success(aiSettingsMap.get(AISettings.OPERATOR));
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

    @PostMapping("/operator/save")
    public RestResponse saveOperatorAISettings(@RequestBody(required = false) AISettings settings) {
        if (!validateAISettings(settings)) {
            return new RestResponse().error("Invalid settings");
        }
        AISettings operatorAISettings = aiSettingsMap.get(AISettings.OPERATOR);
        operatorAISettings.copy(settings);
        aiSettingsMap.put(AISettings.OPERATOR, operatorAISettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(aiSettingsPath), aiSettingsMap);
        } catch (Exception e) {
            log.error("保存 AI operator 设置失败: {}", e.getMessage());
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

    // ==================== 执行体模型列表（clone_assistant_tool） ====================

    /**
     * 查询执行体可选模型列表（供设置页「执行体模型列表」区块展示）。
     * 返回合并视图：内置场景条目（builtIn=true，场景模型实时注入，只读展示）在前、
     * json 追加条目在后；key 撞场景名的追加条目落在原内置位置上（用户覆盖）。
     */
    @RequestMapping("/clone-models/get")
    public RestResponse getCloneModels() {
        return new RestResponse().success(cloneModelSettings.getMergedModels());
    }

    /**
     * 整表保存执行体模型列表的追加部分：设置页在本地完成增删改后一次性提交全部追加条目。
     * 内置场景条目不在此列（由场景设置实时注入，不落盘）；key 撞场景名时该条作为用户覆盖存入 json，
     * 删除后内置条目自动回来。校验通过前不落盘：key 唯一且非空、适配器已注册、模型名非空、
     * reasoningEffort 取值合法，有任何一条不合法就整表拒绝并返回具体位置，避免半成品写坏文件。
     */
    @PostMapping("/clone-models/save")
    public RestResponse saveCloneModels(@RequestBody(required = false) List<CloneModelSettings.ModelEntry> models) {
        String error = validateCloneModels(models);
        if (error != null) {
            return new RestResponse().error(error);
        }
        cloneModelSettings.setModels(models);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(cloneModelSettingsPath), cloneModelSettings);
        } catch (Exception e) {
            log.error("保存执行体模型列表失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    /**
     * 校验并归一化整表记录。返回 null 表示全部通过（记录已在原对象上完成归一化），
     * 返回非空字符串为第一条问题的描述（含第几条、key 是什么）。
     */
    private String validateCloneModels(List<CloneModelSettings.ModelEntry> models) {
        if (models == null) {
            return "模型列表不能为空";
        }
        java.util.Set<String> keys = new HashSet<>();
        java.util.Set<String> availableAdapters = adapterFactory.getAvailableAdapterNames();
        for (int i = 0; i < models.size(); i++) {
            CloneModelSettings.ModelEntry entry = models.get(i);
            String where = "第 " + (i + 1) + " 条";
            if (entry == null) {
                return where + "为空记录";
            }
            String key = entry.getKey() == null ? "" : entry.getKey().trim();
            if (key.isEmpty()) {
                return where + "缺少 key";
            }
            if (!keys.add(key)) {
                return where + "key 重复: " + key;
            }
            String adapter = entry.getAdapterName() == null ? "" : entry.getAdapterName().trim();
            if (adapter.isEmpty()) {
                return where + "（" + key + "）缺少适配器";
            }
            if (!availableAdapters.contains(adapter)) {
                return where + "（" + key + "）适配器未注册: " + adapter;
            }
            if (!StringUtils.hasText(entry.getModel())) {
                return where + "（" + key + "）缺少模型名称";
            }
            // 归一化：去掉首尾空白，thinking 空默认关，effort 空转 null（不写入请求体），
            // builtIn 一律置 null——内置标记只由运行时注入产生，json 只存追加语义
            entry.setKey(key).setAdapterName(adapter).setModel(entry.getModel().trim());
            entry.setThinking(Boolean.TRUE.equals(entry.getThinking()));
            entry.setBuiltIn(null);
            String effort = entry.getReasoningEffort();
            if (StringUtils.hasText(effort)) {
                if (!"low".equals(effort) && !"high".equals(effort) && !"max".equals(effort)) {
                    return where + "（" + key + "）reasoningEffort 取值非法: " + effort + "，允许 low/high/max 或留空";
                }
                entry.setReasoningEffort(effort);
            } else {
                entry.setReasoningEffort(null);
            }
        }
        return null;
    }
}
