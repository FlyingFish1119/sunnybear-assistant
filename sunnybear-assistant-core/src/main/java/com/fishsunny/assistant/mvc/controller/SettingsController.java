package com.fishsunny.assistant.mvc.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import com.fishsunny.assistant.engine.protocol.EmbeddingAPI;
import com.fishsunny.assistant.engine.protocol.embedding.StandardEmbeddingAPI;
import com.fishsunny.assistant.engine.tool.instance.file.FileDeleteTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileDownloadTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileEditTool;
import com.fishsunny.assistant.engine.tool.instance.file.FileWriteTool;
import com.fishsunny.assistant.engine.tool.instance.image.ImageCaptionTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebSearchTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebReaderTool;
import com.fishsunny.assistant.engine.tool.instance.os.CommandTool;
import com.fishsunny.assistant.engine.tool.instance.os.ExtensionScriptTool;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.KnowledgeSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.utils.image.MultipartScaleImageHelper;
import com.fishsunny.assistant.utils.image.ScaleImageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 综合设置控制器
 * <p>
 * 提供所有设置的查询和保存接口，包括用户设置、助手设置、
 * AI 模型设置、工具设置、知识库设置。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class SettingsController {

    private static final Logger log = LoggerFactory.getLogger(SettingsController.class);

    private final ObjectMapper objectMapper;
    private final AIAdapterFactory adapterFactory;

    // ========================= 文件路径 =========================
    private final String fileBasePath;
    private final String userSettingsPath;
    private final String assistantSettingsPath;
    private final String aiSettingsPath;
    private final String toolSettingsPath;
    private final String knowledgeSettingsPath;

    // ========================= 设置 Bean =========================
    private final UserSettings userSettings;
    private final AssistantSettings assistantSettings;
    private final Map<String, AISettings> aiSettingsMap;
    private final Map<String, Object> toolSettingsMap;
    private final Map<String, Object> knowledgeSettingsMap;
    public SettingsController(
            ObjectMapper objectMapper,
            AIAdapterFactory adapterFactory,
            @Value("${assistant.file.base-path:data/}") String fileBasePath,
            @Value("${user-settings.path:settings/user_settings.json}") String userSettingsPath,
            @Value("${assistant-settings.path:settings/assistant_settings.json}") String assistantSettingsPath,
            @Value("${ai-settings.path:settings/ai_settings.json}") String aiSettingsPath,
            @Value("${tool-settings.path:settings/tool_settings.json}") String toolSettingsPath,
            @Value("${knowledge-settings.path:settings/knowledge_settings.json}") String knowledgeSettingsPath,
            UserSettings userSettings,
            AssistantSettings assistantSettings,
            @Qualifier(AISettings.CHAT) AISettings chatAISettings,
            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
            @Qualifier(AISettings.OCR) AISettings ocrAISettings,
            @Qualifier(AISettings.MISSION) AISettings missionAISettings,
            @Qualifier(AISettings.TASK) AISettings taskAISettings,
            @Qualifier(AISettings.CUB) AISettings cubAISettings,
            @Qualifier(CommandTool.SETTINGS) CommandTool.Settings commandToolSettings,
            @Qualifier(ExtensionScriptTool.SETTINGS) ExtensionScriptTool.Settings extensionScriptToolSettings,
            @Qualifier(WebSearchTool.SETTINGS) WebSearchTool.Settings webSearchToolSettings,
            @Qualifier(FileWriteTool.SETTINGS) FileWriteTool.Settings fileWriteToolSettings,
            @Qualifier(FileEditTool.SETTINGS) FileEditTool.Settings fileEditToolSettings,
            @Qualifier(FileDeleteTool.SETTINGS) FileDeleteTool.Settings fileDeleteToolSettings,
            @Qualifier(FileDownloadTool.SETTINGS) FileDownloadTool.Settings fileDownloadToolSettings,
            @Qualifier(ImageCaptionTool.SETTINGS) ImageCaptionTool.Settings imageCaptionToolSettings,
            @Qualifier(WebReaderTool.SETTINGS) WebReaderTool.Settings webReaderToolSettings,
            EmbeddingAPI knowledgeAPI,
            KnowledgeSettings knowledgeSettings) {
        this.objectMapper = objectMapper;
        this.adapterFactory = adapterFactory;
        this.fileBasePath = fileBasePath;
        this.userSettingsPath = userSettingsPath;
        this.assistantSettingsPath = assistantSettingsPath;
        this.aiSettingsPath = aiSettingsPath;
        this.toolSettingsPath = toolSettingsPath;
        this.knowledgeSettingsPath = knowledgeSettingsPath;
        this.userSettings = userSettings;
        this.assistantSettings = assistantSettings;
        this.aiSettingsMap = new LinkedHashMap<>();
        this.aiSettingsMap.put(AISettings.CHAT, chatAISettings);
        this.aiSettingsMap.put(AISettings.CHAT_PRO, chatProAISettings);
        this.aiSettingsMap.put(AISettings.OCR, ocrAISettings);
        this.aiSettingsMap.put(AISettings.MISSION, missionAISettings);
        this.aiSettingsMap.put(AISettings.TASK, taskAISettings);
        this.aiSettingsMap.put(AISettings.CUB, cubAISettings);
        this.toolSettingsMap = new LinkedHashMap<>();
        this.toolSettingsMap.put(CommandTool.SETTINGS, commandToolSettings);
        this.toolSettingsMap.put(ExtensionScriptTool.SETTINGS, extensionScriptToolSettings);
        this.toolSettingsMap.put(WebSearchTool.SETTINGS, webSearchToolSettings);
        this.toolSettingsMap.put(FileWriteTool.SETTINGS, fileWriteToolSettings);
        this.toolSettingsMap.put(FileEditTool.SETTINGS, fileEditToolSettings);
        this.toolSettingsMap.put(FileDeleteTool.SETTINGS, fileDeleteToolSettings);
        this.toolSettingsMap.put(FileDownloadTool.SETTINGS, fileDownloadToolSettings);
        this.toolSettingsMap.put(ImageCaptionTool.SETTINGS, imageCaptionToolSettings);
        this.toolSettingsMap.put(WebReaderTool.SETTINGS, webReaderToolSettings);
        this.knowledgeSettingsMap = new LinkedHashMap<>();
        this.knowledgeSettingsMap.put(KnowledgeSettings.SETTINGS, knowledgeSettings);
        this.knowledgeSettingsMap.put(KnowledgeSettings.API, knowledgeAPI);
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
        // 强制 prompt 为 null，chat_pro 始终从 chat 继承系统提示词
        chatProAISettings.setPrompt(null);
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

    @RequestMapping("/command/get")
    public RestResponse getCommandToolSettings() {
        CommandTool.Settings commandToolSettings = (CommandTool.Settings) toolSettingsMap.get(CommandTool.SETTINGS);
        return new RestResponse().success(commandToolSettings);
    }
    @RequestMapping("/extensionscript/get")
    public RestResponse getExtensionScriptToolSettings() {
        ExtensionScriptTool.Settings extensionScriptToolSettings = (ExtensionScriptTool.Settings) toolSettingsMap.get(ExtensionScriptTool.SETTINGS);
        return new RestResponse().success(extensionScriptToolSettings);
    }
    @RequestMapping("/websearch/get")
    public RestResponse getWebSearchToolSettings() {
        WebSearchTool.Settings webSearchToolSettings = (WebSearchTool.Settings) toolSettingsMap.get(WebSearchTool.SETTINGS);
        return new RestResponse().success(webSearchToolSettings);
    }
    @RequestMapping("/filewrite/get")
    public RestResponse getFileWriteToolSettings() {
        FileWriteTool.Settings fileWriteToolSettings = (FileWriteTool.Settings) toolSettingsMap.get(FileWriteTool.SETTINGS);
        return new RestResponse().success(fileWriteToolSettings);
    }
    @RequestMapping("/fileedit/get")
    public RestResponse getFileEditToolSettings() {
        FileEditTool.Settings fileEditToolSettings = (FileEditTool.Settings) toolSettingsMap.get(FileEditTool.SETTINGS);
        return new RestResponse().success(fileEditToolSettings);
    }
    @RequestMapping("/filedelete/get")
    public RestResponse getFileDeleteToolSettings() {
        FileDeleteTool.Settings fileDeleteToolSettings = (FileDeleteTool.Settings) toolSettingsMap.get(FileDeleteTool.SETTINGS);
        return new RestResponse().success(fileDeleteToolSettings);
    }
    @RequestMapping("/filedownload/get")
    public RestResponse getFileDownloadToolSettings() {
        FileDownloadTool.Settings fileDownloadToolSettings = (FileDownloadTool.Settings) toolSettingsMap.get(FileDownloadTool.SETTINGS);
        return new RestResponse().success(fileDownloadToolSettings);
    }
    @RequestMapping("/imagecaption/get")
    public RestResponse getImageCaptionToolSettings() {
        ImageCaptionTool.Settings imageCaptionToolSettings = (ImageCaptionTool.Settings) toolSettingsMap.get(ImageCaptionTool.SETTINGS);
        return new RestResponse().success(imageCaptionToolSettings);
    }
    @RequestMapping("/webreadertool/get")
    public RestResponse getWebReaderToolSettings() {
        WebReaderTool.Settings webReaderToolSettings = (WebReaderTool.Settings) toolSettingsMap.get(WebReaderTool.SETTINGS);
        return new RestResponse().success(webReaderToolSettings);
    }
    @PostMapping("/command/save")
    public RestResponse saveCommandToolSettings(@RequestBody(required = false) CommandTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(CommandTool.AUTO) &&
                !mode.equals(CommandTool.ALWAYS_ASKED) &&
                !mode.equals(CommandTool.ALWAYS_REJECT_DANGER) &&
                !mode.equals(CommandTool.NEVER_ASKED)) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getBlackList() == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getWhiteList() == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getTimeout() == null || settings.getTimeout() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getSafetyOutputSize() == null || settings.getSafetyOutputSize() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getMaxOutputSize() == null || settings.getMaxOutputSize() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        CommandTool.Settings commandToolSettings = (CommandTool.Settings) toolSettingsMap.get(CommandTool.SETTINGS);
        commandToolSettings.setMode(settings.getMode())
                .setBlackList(settings.getBlackList())
                .setWhiteList(settings.getWhiteList())
                .setTimeout(settings.getTimeout())
                .setSafetyOutputSize(settings.getSafetyOutputSize())
                .setMaxOutputSize(settings.getMaxOutputSize());
        toolSettingsMap.put(CommandTool.SETTINGS, commandToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 CommandTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/extensionscript/save")
    public RestResponse saveExtensionScriptToolSettings(@RequestBody(required = false) ExtensionScriptTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getTimeout() == null || settings.getTimeout() < 1) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getMaxOutputSize() == null || settings.getMaxOutputSize() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        ExtensionScriptTool.Settings extSettings = (ExtensionScriptTool.Settings) toolSettingsMap.get(ExtensionScriptTool.SETTINGS);
        extSettings.setTimeout(settings.getTimeout())
                .setMaxOutputSize(settings.getMaxOutputSize());
        toolSettingsMap.put(ExtensionScriptTool.SETTINGS, extSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 ExtensionScriptTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/websearch/save")
    public RestResponse saveWebSearchToolSettings(@RequestBody(required = false) WebSearchTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        WebSearchTool.Settings webSearchToolSettings = (WebSearchTool.Settings) toolSettingsMap.get(WebSearchTool.SETTINGS);
        if (StringUtils.hasText(settings.getMetasoApiKey())) {
            webSearchToolSettings.setMetasoApiKey(settings.getMetasoApiKey());
        }
        if (StringUtils.hasText(settings.getSerperApiKey())) {
            webSearchToolSettings.setSerperApiKey(settings.getSerperApiKey());
        }
        if (!StringUtils.hasText(webSearchToolSettings.getMetasoApiKey())
                && !StringUtils.hasText(webSearchToolSettings.getSerperApiKey())) {
            return new RestResponse().error("至少需要配置一个搜索引擎的 API Key");
        }
        toolSettingsMap.put(WebSearchTool.SETTINGS, webSearchToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 WebSearchTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/filewrite/save")
    public RestResponse saveFileWriteToolSettings(@RequestBody(required = false) FileWriteTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileWriteTool.AUTO) &&
                !mode.equals(FileWriteTool.ALWAYS_ASKED) &&
                !mode.equals(FileWriteTool.NEVER_ASKED) &&
                !mode.equals(FileWriteTool.ALWAYS_REJECT_DANGER)) {
            return new RestResponse().error("Invalid settings");
        }
        FileWriteTool.Settings fileWriteToolSettings = (FileWriteTool.Settings) toolSettingsMap.get(FileWriteTool.SETTINGS);
        fileWriteToolSettings.setMode(mode);
        toolSettingsMap.put(FileWriteTool.SETTINGS, fileWriteToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 FileWriteTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/fileedit/save")
    public RestResponse saveFileEditToolSettings(@RequestBody(required = false) FileEditTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileEditTool.AUTO) &&
                !mode.equals(FileEditTool.ALWAYS_ASKED) &&
                !mode.equals(FileEditTool.NEVER_ASKED) &&
                !mode.equals(FileEditTool.ALWAYS_REJECT_DANGER)) {
            return new RestResponse().error("Invalid settings");
        }
        FileEditTool.Settings fileEditToolSettings = (FileEditTool.Settings) toolSettingsMap.get(FileEditTool.SETTINGS);
        fileEditToolSettings.setMode(mode);
        toolSettingsMap.put(FileEditTool.SETTINGS, fileEditToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 FileEditTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/filedelete/save")
    public RestResponse saveFileDeleteToolSettings(@RequestBody(required = false) FileDeleteTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileDeleteTool.AUTO) &&
                !mode.equals(FileDeleteTool.ALWAYS_ASKED) &&
                !mode.equals(FileDeleteTool.NEVER_ASKED) &&
                !mode.equals(FileDeleteTool.ALWAYS_REJECT_DANGER)) {
            return new RestResponse().error("Invalid settings");
        }
        FileDeleteTool.Settings fileDeleteToolSettings = (FileDeleteTool.Settings) toolSettingsMap.get(FileDeleteTool.SETTINGS);
        fileDeleteToolSettings.setMode(mode);
        toolSettingsMap.put(FileDeleteTool.SETTINGS, fileDeleteToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            log.error("保存 FileDeleteTool 设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/filedownload/save")
    public RestResponse saveFileDownloadToolSettings(@RequestBody(required = false) FileDownloadTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        String mode = settings.getMode();
        if (!StringUtils.hasText(settings.getMode())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!mode.equals(FileDownloadTool.ALWAYS_ASKED) &&
                !mode.equals(FileDownloadTool.NEVER_ASKED)) {
            return new RestResponse().error("Invalid settings");
        }
        FileDownloadTool.Settings fileDownloadToolSettings = (FileDownloadTool.Settings) toolSettingsMap.get(FileDownloadTool.SETTINGS);
        fileDownloadToolSettings.setMode(mode);
        toolSettingsMap.put(FileDownloadTool.SETTINGS, fileDownloadToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/imagecaption/save")
    public RestResponse saveImageCaptionToolSettings(@RequestBody(required = false) ImageCaptionTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getMaxLength() == null || settings.getMaxLength() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        ImageCaptionTool.Settings captionTool = (ImageCaptionTool.Settings) toolSettingsMap.get(ImageCaptionTool.SETTINGS);
        captionTool.setMaxLength(settings.getMaxLength());
        toolSettingsMap.put(ImageCaptionTool.SETTINGS, captionTool);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/webreadertool/save")
    public RestResponse saveWebReaderToolSettings(@RequestBody(required = false) WebReaderTool.Settings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getBrowserTimeoutMs() == null || settings.getBrowserTimeoutMs() < 1000) {
            return new RestResponse().error("浏览器超时至少为 1000ms");
        }
        WebReaderTool.Settings webReaderToolSettings = (WebReaderTool.Settings) toolSettingsMap.get(WebReaderTool.SETTINGS);
        webReaderToolSettings.setBrowserTimeoutMs(settings.getBrowserTimeoutMs());
        toolSettingsMap.put(WebReaderTool.SETTINGS, webReaderToolSettings);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(toolSettingsPath), toolSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @RequestMapping("/knowledgesettings/get")
    public RestResponse getKnowledgeSettings() {
        KnowledgeSettings knowledgeSettings = (KnowledgeSettings) knowledgeSettingsMap.get(KnowledgeSettings.SETTINGS);
        return new RestResponse().success(knowledgeSettings);
    }
    @RequestMapping("/knowledgeapi/get")
    public RestResponse getKnowledgeAPI() {
        StandardEmbeddingAPI knowledgeAPI = (StandardEmbeddingAPI) knowledgeSettingsMap.get(KnowledgeSettings.API);
        return new RestResponse().success(knowledgeAPI);
    }
    @PostMapping("/knowledge/save")
    public RestResponse saveKnowledgeSettings(@RequestBody(required = false) KnowledgeSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getEnable() == null) {
            settings.setEnable(false);
        }
        if (settings.getSimilarityThreshold() == null || settings.getSimilarityThreshold() < 0) {
            return new RestResponse().error("Invalid settings");
        }
        KnowledgeSettings knowledgeSettings = (KnowledgeSettings) knowledgeSettingsMap.get(KnowledgeSettings.SETTINGS);
        knowledgeSettings.setEnable(settings.getEnable())
                .setSimilarityThreshold(settings.getSimilarityThreshold());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(knowledgeSettingsPath), knowledgeSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
    @PostMapping("/knowledgeapi/save")
    public RestResponse saveKnowledgeAPI(@RequestBody(required = false) StandardEmbeddingAPI settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getModel())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getUrl())) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getApiKey())) {
            return new RestResponse().error("Invalid settings");
        }
        EmbeddingAPI knowledgeAPI = (EmbeddingAPI) knowledgeSettingsMap.get(KnowledgeSettings.API);
        knowledgeAPI.setModel(settings.getModel())
                .setUrl(settings.getUrl())
                .setApiKey(settings.getApiKey());
        knowledgeSettingsMap.put(KnowledgeSettings.API, knowledgeAPI);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(knowledgeSettingsPath), knowledgeSettingsMap);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    @RequestMapping("/user/get")
    public RestResponse getUserSettings() {
        return new RestResponse().success(userSettings);
    }
    @PostMapping("/user/save")
    public RestResponse saveUserSettings(@RequestBody(required = false) UserSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getUsername())) {
            return new RestResponse().error("Invalid settings");
        }
        if (settings.getOpacity() == null) {
            settings.setOpacity(0.3);
        }
        if (!StringUtils.hasText(settings.getMainColor())) {
            settings.setMainColor("lightsalmon");
        }
        userSettings.setUsername(settings.getUsername());
        userSettings.setOpacity(settings.getOpacity());
        userSettings.setMainColor(settings.getMainColor());
        userSettings.setEnableAutoSwitchModel(
                settings.getEnableAutoSwitchModel() != null ? settings.getEnableAutoSwitchModel() : false);
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    // ==================== 用户头像上传 / 删除 ====================

    @PostMapping("/user/avatar/upload")
    public RestResponse uploadUserAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !ScaleImageHelper.checkIsImage(originalFilename)) {
            return new RestResponse().error("不支持的图片格式");
        }
        try {
            String userDir = fileBasePath + "user/";
            new File(userDir).mkdirs();
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            byte[] scaled = new MultipartScaleImageHelper(file).scaleImage(256);
            String filename = "avatar" + ext;
            Path target = Paths.get(userDir, filename);
            Files.write(target, scaled);
            String path = target.toString().replace('\\', '/');
            userSettings.setAvatar(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            log.info("用户头像已上传: {}", path);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传用户头像失败", e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/user/avatar/delete")
    public RestResponse deleteUserAvatar() {
        try {
            String oldPath = userSettings.getAvatar();
            if (oldPath != null && !oldPath.isEmpty()) {
                File oldFile = new File(oldPath);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除用户头像文件失败: {}", oldPath);
                    throw new RuntimeException("删除用户头像文件失败");
                }
            }
            userSettings.setAvatar("");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            return new RestResponse().success("已删除");
        } catch (Exception e) {
            log.error("删除用户头像失败", e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }

    // ==================== 助手头像上传 / 删除 ====================

    @PostMapping("/assistant/avatar/upload")
    public RestResponse uploadAssistantAvatar(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !ScaleImageHelper.checkIsImage(originalFilename)) {
            return new RestResponse().error("不支持的图片格式");
        }
        try {
            String userDir = fileBasePath + "user/";
            new File(userDir).mkdirs();
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            byte[] scaled = new MultipartScaleImageHelper(file).scaleImage(256);
            String filename = "assistant_avatar" + ext;
            java.nio.file.Path target = Paths.get(userDir, filename);
            Files.write(target, scaled);
            String path = target.toString().replace('\\', '/');
            assistantSettings.setAvatar(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(assistantSettingsPath), assistantSettings);
            log.info("助手头像已上传: {}", path);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传助手头像失败", e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/assistant/avatar/delete")
    public RestResponse deleteAssistantAvatar() {
        try {
            String oldPath = assistantSettings.getAvatar();
            if (oldPath != null && !oldPath.isEmpty()) {
                File oldFile = new File(oldPath);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除助手头像文件失败: {}", oldPath);
                    throw new RuntimeException("删除助手头像文件失败");
                }
            }
            assistantSettings.setAvatar("");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(assistantSettingsPath), assistantSettings);
            return new RestResponse().success("已删除");
        } catch (Exception e) {
            log.error("删除助手头像失败", e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }

    // ==================== 用户背景上传 / 删除 ====================

    @PostMapping("/user/background/upload")
    public RestResponse uploadUserBackground(@RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || !ScaleImageHelper.checkIsImage(originalFilename)) {
            return new RestResponse().error("不支持的图片格式");
        }
        try {
            String userDir = fileBasePath + "user/";
            new File(userDir).mkdirs();
            String ext = originalFilename.substring(originalFilename.lastIndexOf("."));
            byte[] scaled = new MultipartScaleImageHelper(file).scaleImage(1920);
            String filename = "background" + ext;
            java.nio.file.Path target = Paths.get(userDir, filename);
            Files.write(target, scaled);
            String path = target.toString().replace('\\', '/');
            userSettings.setBackground(path);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            log.info("用户背景已上传: {}", path);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传用户背景失败", e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    @PostMapping("/user/background/delete")
    public RestResponse deleteUserBackground() {
        try {
            String oldPath = userSettings.getBackground();
            if (oldPath != null && !oldPath.isEmpty()) {
                File oldFile = new File(oldPath);
                if (oldFile.exists() && !oldFile.delete()) {
                    log.warn("删除用户背景文件失败: {}", oldPath);
                }
            }
            userSettings.setBackground("");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(userSettingsPath), userSettings);
            return new RestResponse().success("已删除");
        } catch (Exception e) {
            log.error("删除用户背景失败", e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }

    @RequestMapping("/assistant/get")
    public RestResponse getAssistantSettings() {
        return new RestResponse().success(assistantSettings);
    }
    @PostMapping("/assistant/save")
    public RestResponse saveAssistantSettings(@RequestBody(required = false) AssistantSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        if (!StringUtils.hasText(settings.getAssistantName())) {
            return new RestResponse().error("Invalid settings");
        }
        assistantSettings.setAssistantName(settings.getAssistantName());
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(new File(assistantSettingsPath), assistantSettings);
        } catch (Exception e) {
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }
}
