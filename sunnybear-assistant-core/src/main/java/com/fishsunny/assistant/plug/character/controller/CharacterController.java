package com.fishsunny.assistant.plug.character.controller;

/*
 * @Usage 角色管理控制器，提供角色的 CRUD、按角色过滤会话、角色激活等功能
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.plug.character.db.CharacterDbManager;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import com.fishsunny.assistant.plug.character.service.CharacterSessionBindings;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/character")
public class CharacterController {

    private static final Logger log = LoggerFactory.getLogger(CharacterController.class);

    /** 角色名称最大长度 */
    private static final int MAX_NAME_LENGTH = 50;
    /** 角色设定最大长度 */
    private static final int MAX_SETTING_LENGTH = 50000;

    private final ObjectMapper objectMapper;
    private final CharacterDbManager characterDbManager;
    private final CharacterInfoService characterInfoService;
    private final ChatSessionService chatSessionService;
    private final AssistantSettings assistantSettings;
    private final UserSettings userSettings;
    private final AISettings chatAISettings;
    private final String assistantSettingsPath;
    private final String aiSettingsPath;
    private final String userSettingsPath;
    private final Map<String, AISettings> aiSettingsMap;

    @Autowired
    public CharacterController(
            ObjectMapper objectMapper,
            CharacterDbManager characterDbManager,
            CharacterInfoService characterInfoService,
            ChatSessionService chatSessionService,
            AssistantSettings assistantSettings,
            UserSettings userSettings,
            @Qualifier(AISettings.CHAT) AISettings chatAISettings,
            @Qualifier(AISettings.OCR) AISettings ocrAISettings,
            @Qualifier(AISettings.MISSION) AISettings missionAISettings,
            @Qualifier(AISettings.TASK) AISettings taskAISettings,
            @Qualifier(AISettings.CUB) AISettings cubAISettings,
            @Value("${assistant-settings.path:settings/assistant_settings.json}") String assistantSettingsPath,
            @Value("${ai-settings.path:settings/ai_settings.json}") String aiSettingsPath,
            @Value("${user-settings.path:settings/user_settings.json}") String userSettingsPath) {
        this.objectMapper = objectMapper;
        this.characterDbManager = characterDbManager;
        this.characterInfoService = characterInfoService;
        this.chatSessionService = chatSessionService;
        this.assistantSettings = assistantSettings;
        this.userSettings = userSettings;
        this.chatAISettings = chatAISettings;
        this.assistantSettingsPath = assistantSettingsPath;
        this.aiSettingsPath = aiSettingsPath;
        this.userSettingsPath = userSettingsPath;
        this.aiSettingsMap = new LinkedHashMap<>();
        this.aiSettingsMap.put(AISettings.CHAT, chatAISettings);
        this.aiSettingsMap.put(AISettings.OCR, ocrAISettings);
        this.aiSettingsMap.put(AISettings.MISSION, missionAISettings);
        this.aiSettingsMap.put(AISettings.TASK, taskAISettings);
        this.aiSettingsMap.put(AISettings.CUB, cubAISettings);
    }

    // ==================== 角色 CRUD ====================

    @RequestMapping("/list")
    public RestResponse list() {
        try {
            List<CharacterInfo> characters = characterInfoService.findAll();
            return new RestResponse().success(characters);
        } catch (Exception e) {
            log.error("获取角色列表失败", e);
            return new RestResponse().error("获取角色列表失败: " + e.getMessage());
        }
    }

    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            CharacterInfo character = characterInfoService.findById(id);
            if (character == null) {
                return new RestResponse().error("角色不存在");
            }
            return new RestResponse().success(character);
        } catch (Exception e) {
            log.error("获取角色失败", e);
            return new RestResponse().error("获取角色失败: " + e.getMessage());
        }
    }

    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) CharacterInfo character) {
        if (character == null) {
            return new RestResponse().error("角色信息不能为空");
        }
        if (!StringUtils.hasText(character.getName())) {
            return new RestResponse().error("角色名称不能为空");
        }
        if (character.getName().length() > MAX_NAME_LENGTH) {
            return new RestResponse().error("角色名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        try {
            CharacterInfo saved = characterInfoService.save(character);
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("创建角色失败", e);
            return new RestResponse().error("创建角色失败: " + e.getMessage());
        }
    }

    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) CharacterInfo character) {
        if (character == null) {
            return new RestResponse().error("角色信息不能为空");
        }
        if (!StringUtils.hasText(character.getId())) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        if (!StringUtils.hasText(character.getName())) {
            return new RestResponse().error("角色名称不能为空");
        }
        if (character.getName().length() > MAX_NAME_LENGTH) {
            return new RestResponse().error("角色名称不能超过" + MAX_NAME_LENGTH + "个字符");
        }
        try {
            CharacterInfo existing = characterInfoService.findById(character.getId());
            if (existing == null) {
                return new RestResponse().error("角色不存在");
            }
            CharacterInfo updated = characterInfoService.update(character);
            return new RestResponse().success(updated);
        } catch (Exception e) {
            log.error("更新角色失败", e);
            return new RestResponse().error("更新角色失败: " + e.getMessage());
        }
    }

    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            characterInfoService.deleteById(id);
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除角色失败", e);
            return new RestResponse().error("删除角色失败: " + e.getMessage());
        }
    }

    /**
     * 单独删除角色背景图（文件 + 清空 DB 字段）。
     */
    @RequestMapping("/delete-background")
    public RestResponse deleteBackground(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            characterInfoService.deleteBackground(id);
            return new RestResponse().success("背景图已删除");
        } catch (Exception e) {
            log.error("删除角色背景图失败", e);
            return new RestResponse().error("删除背景图失败: " + e.getMessage());
        }
    }

    /**
     * 摧毁角色私有数据库 —— 关闭连接池并删除数据库文件，用于重新开始新故事。
     */
    @RequestMapping("/destroy-db")
    public RestResponse destroyDatabase(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            CharacterInfo character = characterInfoService.findById(id);
            if (character == null) {
                return new RestResponse().error("角色不存在");
            }
            String msg = characterDbManager.destroy(id);
            return new RestResponse().success(msg);
        } catch (Exception e) {
            log.error("摧毁角色 [{}] 数据库失败", id, e);
            return new RestResponse().error("摧毁数据库失败: " + e.getMessage());
        }
    }

    /**
     * 获取角色私有数据库的所有表结构及全部数据。
     * 返回每个表的列名列表和所有行数据，用于前端展示。
     */
    @RequestMapping("/db-tables")
    public RestResponse getDbTables(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            CharacterInfo character = characterInfoService.findById(id);
            if (character == null) {
                return new RestResponse().error("角色不存在");
            }
            javax.sql.DataSource ds = characterDbManager.getOrCreate(id);
            List<Map<String, Object>> tables = new ArrayList<>();

            try (java.sql.Connection conn = ds.getConnection();
                 java.sql.Statement stmt = conn.createStatement()) {

                // 1. 获取所有用户表名
                java.sql.ResultSet tableRs = stmt.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name");
                List<String> tableNames = new ArrayList<>();
                while (tableRs.next()) {
                    tableNames.add(tableRs.getString("name"));
                }
                tableRs.close();

                // 2. 对每个表获取列信息和全部数据
                for (String tableName : tableNames) {
                    Map<String, Object> tableInfo = new LinkedHashMap<>();
                    tableInfo.put("tableName", tableName);

                    // 列信息
                    List<Map<String, Object>> columns = new ArrayList<>();
                    java.sql.ResultSet colRs = stmt.executeQuery("PRAGMA table_info('" + tableName.replace("'", "''") + "')");
                    List<String> colNames = new ArrayList<>();
                    while (colRs.next()) {
                        Map<String, Object> col = new LinkedHashMap<>();
                        col.put("name", colRs.getString("name"));
                        col.put("type", colRs.getString("type"));
                        col.put("notNull", colRs.getInt("notnull") == 1);
                        col.put("pk", colRs.getInt("pk") == 1);
                        columns.add(col);
                        colNames.add(colRs.getString("name"));
                    }
                    colRs.close();
                    tableInfo.put("columns", columns);

                    // 全部数据行
                    List<Map<String, Object>> rows = new ArrayList<>();
                    try {
                        java.sql.ResultSet dataRs = stmt.executeQuery("SELECT * FROM \"" + tableName.replace("\"", "\"\"") + "\"");
                        while (dataRs.next()) {
                            Map<String, Object> row = new LinkedHashMap<>();
                            for (String colName : colNames) {
                                row.put(colName, dataRs.getString(colName));
                            }
                            rows.add(row);
                        }
                        dataRs.close();
                    } catch (Exception e) {
                        log.warn("读取表 [{}] 数据失败: {}", tableName, e.getMessage());
                        tableInfo.put("error", e.getMessage());
                    }
                    tableInfo.put("rows", rows);
                    tableInfo.put("rowCount", rows.size());

                    tables.add(tableInfo);
                }
            }

            return new RestResponse().success(tables);
        } catch (Exception e) {
            log.error("获取角色 [{}] 数据库表失败", id, e);
            return new RestResponse().error("获取数据库表失败: " + e.getMessage());
        }
    }

    /**
     * 单独上传角色背景图（multipart 文件上传，与 create/update 解耦）。
     */
    @PostMapping("/upload-background")
    public RestResponse uploadBackground(@RequestParam("id") String id,
                                         @RequestParam("file") MultipartFile file) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        try {
            String path = characterInfoService.uploadBackground(id, file);
            return new RestResponse().success(path);
        } catch (Exception e) {
            log.error("上传角色背景图失败", e);
            return new RestResponse().error("上传背景图失败: " + e.getMessage());
        }
    }

    // ==================== 角色激活 ====================

    /**
     * 激活角色 —— 将该角色的设定应用到 assistant settings 和 AI chat settings
     */
    @PostMapping("/activate")
    public RestResponse activate(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        String characterId = id;
        try {
            CharacterInfo character = characterInfoService.findById(characterId);
            if (character == null) {
                return new RestResponse().error("角色不存在");
            }

            // 1. 更新 AssistantSettings
            assistantSettings.setAssistantName(character.getName());
            if (StringUtils.hasText(character.getAvatar())) {
                assistantSettings.setAvatar(character.getAvatar());
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    new File(assistantSettingsPath), assistantSettings);

            // 2. 更新 AI Chat Settings（从 aiSettings JSON 解析完整参数，含 prompt）
            if (StringUtils.hasText(character.getAiSettings())) {
                try {
                    AISettings charAi = objectMapper.readValue(character.getAiSettings(), AISettings.class);
                    if (StringUtils.hasText(charAi.getPrompt())) {
                        chatAISettings.setPrompt(charAi.getPrompt());
                    }
                    if (StringUtils.hasText(charAi.getAdapterName())) {
                        chatAISettings.setAdapterName(charAi.getAdapterName());
                    }
                    if (StringUtils.hasText(charAi.getModel())) {
                        chatAISettings.setModel(charAi.getModel());
                    }
                    chatAISettings.setStream(charAi.getStream());
                    chatAISettings.setThinking(charAi.getThinking());
                    chatAISettings.setTemperature(charAi.getTemperature());
                    chatAISettings.setTop_p(charAi.getTop_p());
                    chatAISettings.setMaxTokens(charAi.getMaxTokens());
                    chatAISettings.setFrequencyPenalty(charAi.getFrequencyPenalty());
                    chatAISettings.setPresencePenalty(charAi.getPresencePenalty());
                } catch (Exception e) {
                    log.warn("解析角色 [{}] aiSettings JSON 失败: {}", characterId, e.getMessage());
                }
            }
            aiSettingsMap.put(AISettings.CHAT, chatAISettings);
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                    new File(aiSettingsPath), aiSettingsMap);

            // 3. 更新 UserSettings（背景图、主题色、透明度）
            boolean userSettingsChanged = false;
            if (StringUtils.hasText(character.getBackground())) {
                userSettings.setBackground(character.getBackground());
                userSettingsChanged = true;
            }
            if (StringUtils.hasText(character.getMainColor())) {
                userSettings.setMainColor(character.getMainColor());
                userSettingsChanged = true;
            }
            if (character.getOpacity() != null) {
                userSettings.setOpacity(character.getOpacity());
                userSettingsChanged = true;
            }
            if (userSettingsChanged) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(
                        new File(userSettingsPath), userSettings);
            }

            log.info("角色 [{}] 已激活: name={}, aiSettings={}, mainColor={}, opacity={}, background={}",
                    characterId, character.getName(), character.getAiSettings(),
                    character.getMainColor(), character.getOpacity(), character.getBackground());
            return new RestResponse().success(character);
        } catch (Exception e) {
            log.error("激活角色失败", e);
            return new RestResponse().error("激活角色失败: " + e.getMessage());
        }
    }

    // ==================== 角色会话管理 ====================

    @RequestMapping("/sessions")
    public RestResponse getSessionsByCharacterId(@RequestParam("characterId") String characterId) {
        if (!StringUtils.hasText(characterId)) {
            return new RestResponse().error("角色 ID 不能为空");
        }
        try {
            List<ChatSession> sessions = chatSessionService.findByTypeAndExtensionValue(
                    CharacterSessionBindings.SESSION_TYPE, CharacterSessionBindings.EXTENSION_KEY, characterId);
            return new RestResponse().success(sessions);
        } catch (Exception e) {
            log.error("获取角色会话失败", e);
            return new RestResponse().error("获取角色会话失败: " + e.getMessage());
        }
    }

    @RequestMapping("/get-by-session")
    public RestResponse getCharacterBySessionId(@RequestParam("sessionId") String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            ChatSession session = chatSessionService.findById(sessionId);
            String characterId = CharacterSessionBindings.resolveCharacterId(session);
            if (characterId == null) {
                return new RestResponse().success(null);
            }
            CharacterInfo character = characterInfoService.findById(characterId);
            return new RestResponse().success(character);
        } catch (Exception e) {
            log.error("通过会话获取角色失败", e);
            return new RestResponse().error("获取角色失败: " + e.getMessage());
        }
    }

}
