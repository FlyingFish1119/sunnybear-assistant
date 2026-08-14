package com.fishsunny.assistant.settings;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 用户设置加载器
 * <p>
 * 在程序启动时自动读取项目下的 user_settings.json 文件，
 * 并将其反序列化为 {@link UserSettings} 对象装配到 Spring 容器中。
 * <p>
 * 使用方式：在任意 Spring 管理的组件中通过 @Autowired 注入即可
 * <pre>
 *     @Autowired
 *     private UserSettings userSettings;
 * </pre>
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/6/27
 */
@Configuration
public class SettingsLoader {

    private final Logger log = LoggerFactory.getLogger(SettingsLoader.class);

    // ============================== 字段 & 构造 ==============================

    /**
     * 用户设置文件的路径，可在 application.yml 中通过 user-settings.path 配置
     * 默认值为运行目录下的 user_settings.json
     */
    @Value("${user-settings.path:settings/user_settings.json}")
    private String userSettingsPath;
    @Value("${assistant-settings.path:settings/assistant_settings.json}")
    private String assistantSettingsPath;
    @Value("${ai-settings.path:settings/ai_settings.json}")
    private String aiSettingsPath;
    @Value("${tool-settings.path:settings/tool_settings.json}")
    private String toolSettingsPath;
    @Value("${knowledge-settings.path:settings/knowledge_settings.json}")
    private String knowledgeSettingsPath;

    private final ObjectMapper objectMapper;

    public SettingsLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // =========================== 用户 & 助手设置 ===========================

    /**
     * 读取并解析用户设置文件，装配为 Spring Bean
     * <p>
     * 读取流程：
     * <ol>
     *   <li>根据配置的路径定位 JSON 文件</li>
     *   <li>使用 Jackson 将 JSON 反序列化为 UserSettings 对象</li>
     *   <li>若文件不存在或格式错误，返回带有默认值的 UserSettings 对象</li>
     * </ol>
     *
     * @return 从文件中解析的 UserSettings，或默认的 UserSettings（当读取失败时）
     */
    @Bean
    public UserSettings userSettings() {
        File settingsFile = new File(userSettingsPath);

        // 检查路径是否存在
        if (!settingsFile.exists()) {
            log.warn("用户设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
            return new UserSettings();
        }

        // 检查是否为文件
        if (!settingsFile.isFile()) {
            log.warn("用户设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
            return new UserSettings();
        }

        try {
            // 读取并解析 JSON 文件
            UserSettings settings = objectMapper.readValue(settingsFile, UserSettings.class);
            log.info("用户设置文件加载成功: {}，内容: username={}, avatar={}, background={}, opacity={}",
                    settingsFile.getAbsolutePath(),
                    settings.getUsername(),
                    settings.getAvatar(),
                    settings.getBackground(),
                    settings.getOpacity());
            return settings;

        } catch (IOException e) {
            log.error("读取用户设置文件失败: {}，原因: {}，将使用默认设置",
                    settingsFile.getAbsolutePath(), e.getMessage(), e);
            return new UserSettings();
        }
    }

    @Bean
    public AssistantSettings assistantSettings() {
        File settingsFile = new File(assistantSettingsPath);

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

    // ============================== AI 模型设置 ==============================

    private Map<String, AISettings> aiSettingsCache = null;

    @Bean(AISettings.CHAT)
    public AISettings chatAISettings() {
        initAISettingsFile();
        return aiSettingsCache.getOrDefault(AISettings.CHAT, new AISettings());
    }

    @Bean(AISettings.CHAT_PRO)
    public AISettings chatProAISettings() {
        initAISettingsFile();
        AISettings chatPro = aiSettingsCache.getOrDefault(AISettings.CHAT_PRO, new AISettings());
        // 若 prompt 为空，从 chat 继承
        if (!org.springframework.util.StringUtils.hasText(chatPro.getPrompt())) {
            AISettings chat = aiSettingsCache.getOrDefault(AISettings.CHAT, new AISettings());
            chatPro.setPrompt(chat.getPrompt());
        }
        return chatPro;
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
            File settingsFile = new File(aiSettingsPath);
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
                AISettings.CHAT, new AISettings().setModel("deepseek-v4-pro").setStream(true),
                AISettings.CHAT_PRO, new AISettings().setModel("deepseek-v4-pro").setStream(true),
                AISettings.OCR, new AISettings().setModel("kimi-k2.6").setStream(false),
                AISettings.MISSION, new AISettings().setModel("deepseek-v4-flash").setStream(false),
                AISettings.TASK, new AISettings().setModel("deepseek-v4-flash").setStream(false),
                AISettings.CUB, new AISettings().setModel("deepseek-v4-flash").setStream(false)
        );
    }

    // ============================= Embedding设置 ============================

    private Map<String, Object> knowledgeSettingsCache = null;

    private void initKnowledgeSettingsFile() {
        synchronized (this) {
            if (knowledgeSettingsCache != null) {
                return;
            }
            File settingsFile = new File(knowledgeSettingsPath);
            if (!settingsFile.exists()) {
                log.warn("知识库设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
                knowledgeSettingsCache = loadDefaultKnowledgeSettings();
                return;
            }
            if (!settingsFile.isFile()) {
                log.warn("知识库设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
                knowledgeSettingsCache = loadDefaultKnowledgeSettings();
                return;
            }
            try {
                JavaType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                knowledgeSettingsCache = objectMapper.readValue(settingsFile, mapType);
            } catch (IOException e) {
                log.warn("知识库设置文件读取失败: {}，将使用默认设置", settingsFile.getAbsolutePath());
                knowledgeSettingsCache = loadDefaultKnowledgeSettings();
                return;
            }
        }
    }

    private Map<String, Object> loadDefaultKnowledgeSettings() {
        return Map.of(
                "api", new StandardEmbeddingAPI(),
                "settings", new KnowledgeSettings()
                        .setEnable(false)
                        .setSimilarityThreshold(0.7f)
        );
    }

    @Bean
    public EmbeddingAPI knowledgeAPI() {
        initKnowledgeSettingsFile();
        return objectMapper.convertValue(knowledgeSettingsCache.get(KnowledgeSettings.API), StandardEmbeddingAPI.class);
    }

    @Bean
    public KnowledgeSettings knowledgeSettings() {
        initKnowledgeSettingsFile();
        return objectMapper.convertValue(knowledgeSettingsCache.get(KnowledgeSettings.SETTINGS), KnowledgeSettings.class);
    }

    // ============================== 工具设置 ==============================

    private Map<String, Object> toolSettingsCache = null;

    private void initToolSettingsFile() {
        synchronized (this) {
            if (toolSettingsCache != null) {
                return;
            }
            File settingsFile = new File(toolSettingsPath);
            if (!settingsFile.exists()) {
                log.warn("工具设置文件不存在: {}，将使用默认设置", settingsFile.getAbsolutePath());
                toolSettingsCache = loadDefaultToolSettings();
                return;
            }
            if (!settingsFile.isFile()) {
                log.warn("工具设置路径不是一个有效的文件: {}，将使用默认设置", settingsFile.getAbsolutePath());
                toolSettingsCache = loadDefaultToolSettings();
                return;
            }
            try {
                JavaType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class);
                toolSettingsCache = objectMapper.readValue(settingsFile, mapType);
            } catch (IOException e) {
                log.warn("工具设置文件读取失败: {}，将使用默认设置", settingsFile.getAbsolutePath());
                toolSettingsCache = loadDefaultToolSettings();
                return;
            }
        }
    }

    private Map<String, Object> loadDefaultToolSettings() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put(CommandTool.SETTINGS, new CommandTool.Settings(CommandTool.AUTO)
                .setWhiteList(List.of(
                        "dir", "echo", "cd", "type", "set", "help", "ver", "date", "time",
                        "whoami", "hostname", "ipconfig", "nslookup", "netstat", "tasklist",
                        "where", "findstr", "tree", "cls", "path", "assoc", "ftype"
                ))
                .setBlackList(List.of(
                        "rmdir /s", "del /f", "del /s", "format", "diskpart",
                        "reg delete", "reg add", "bcdedit", "shutdown", "netsh",
                        "icacls", "takeown", "cacls", "sc delete", "wmic delete"
                ))
                .setMaxOutputSize(32768L).setSafetyOutputSize(8192L));
        defaults.put(WebSearchTool.SETTINGS, new WebSearchTool.Settings("", ""));
        defaults.put(ExtensionScriptTool.SETTINGS, new ExtensionScriptTool.Settings(30L, 65536L));
        defaults.put(ImageCaptionTool.SETTINGS, new ImageCaptionTool.Settings(1280));
        defaults.put(FileWriteTool.SETTINGS, new FileWriteTool.Settings(FileWriteTool.AUTO));
        defaults.put(FileEditTool.SETTINGS, new FileEditTool.Settings(FileEditTool.AUTO));
        defaults.put(FileDeleteTool.SETTINGS, new FileDeleteTool.Settings(FileDeleteTool.AUTO));
        defaults.put(FileDownloadTool.SETTINGS, new FileDownloadTool.Settings(FileDownloadTool.ALWAYS_ASKED));
        defaults.put(WebReaderTool.SETTINGS, new WebReaderTool.Settings());
        return defaults;
    }

    @Bean(CommandTool.SETTINGS)
    public CommandTool.Settings commandToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(CommandTool.SETTINGS);
        if (data == null) {
            return new CommandTool.Settings(CommandTool.AUTO);
        }
        return objectMapper.convertValue(data, CommandTool.Settings.class);
    }

    @Bean(ExtensionScriptTool.SETTINGS)
    public ExtensionScriptTool.Settings extensionScriptToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(ExtensionScriptTool.SETTINGS);
        if (data == null) {
            return new ExtensionScriptTool.Settings(30L, 65536L);
        }
        return objectMapper.convertValue(data, ExtensionScriptTool.Settings.class);
    }

    @Bean(WebSearchTool.SETTINGS)
    public WebSearchTool.Settings webSearchToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(WebSearchTool.SETTINGS);
        if (data == null) {
            return new WebSearchTool.Settings("", "");
        }
        return objectMapper.convertValue(data, WebSearchTool.Settings.class);
    }

    @Bean(FileWriteTool.SETTINGS)
    public FileWriteTool.Settings fileWriteToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileWriteTool.SETTINGS);
        if (data == null) {
            return new FileWriteTool.Settings(FileWriteTool.AUTO);
        }
        return objectMapper.convertValue(data, FileWriteTool.Settings.class);
    }

    @Bean(FileEditTool.SETTINGS)
    public FileEditTool.Settings fileEditToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileEditTool.SETTINGS);
        if (data == null) {
            return new FileEditTool.Settings(FileEditTool.AUTO);
        }
        return objectMapper.convertValue(data, FileEditTool.Settings.class);
    }

    @Bean(FileDeleteTool.SETTINGS)
    public FileDeleteTool.Settings fileDeleteToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileDeleteTool.SETTINGS);
        if (data == null) {
            return new FileDeleteTool.Settings(FileDeleteTool.AUTO);
        }
        return objectMapper.convertValue(data, FileDeleteTool.Settings.class);
    }

    @Bean(FileDownloadTool.SETTINGS)
    public FileDownloadTool.Settings fileDownloadToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(FileDownloadTool.SETTINGS);
        if (data == null) {
            return new FileDownloadTool.Settings(FileDownloadTool.ALWAYS_ASKED);
        }
        return objectMapper.convertValue(data, FileDownloadTool.Settings.class);
    }

    @Bean(ImageCaptionTool.SETTINGS)
    public ImageCaptionTool.Settings imageCaptionToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(ImageCaptionTool.SETTINGS);
        if (data == null) {
            return new ImageCaptionTool.Settings();
        }
        return objectMapper.convertValue(data, ImageCaptionTool.Settings.class);
    }

    @Bean(WebReaderTool.SETTINGS)
    public WebReaderTool.Settings webReaderToolSettings() {
        initToolSettingsFile();
        Object data = toolSettingsCache.get(WebReaderTool.SETTINGS);
        if (data == null) {
            return new WebReaderTool.Settings();
        }
        return objectMapper.convertValue(data, WebReaderTool.Settings.class);
    }
}
