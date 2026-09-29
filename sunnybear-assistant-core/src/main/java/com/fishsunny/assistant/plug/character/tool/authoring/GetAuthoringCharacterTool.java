package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 查看角色 / 生成角色卡工作目录 —— 不传参数列出全部角色；传入 id 时把该角色摊成
 *        {会话文件目录}/{角色ID}/ 下的五个文件（setting.md / preset.md / character_settings.json /
 *        select_format.md / glossary.json），返回文件清单供文件工具直接读写。
 *        传入 template=true 时不查角色，固定生成一份空白模板到 template/ 下，
 *        其中 character_settings.json 预填默认值：mainColor / opacity 取用户设置、模型参数取主对话 AI。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.plug.character.dto.GlossaryCardItem;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.utils.SessionFileManager;
import lombok.Data;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterAuthoringToolKit.class)
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.get.enable:true}")
public class GetAuthoringCharacterTool implements ToolHandler {

    public static final String NAME = "character_authoring_get_tool";

    /** 角色卡工作目录内的文件 */
    private static final String FILE_SETTING = "setting.md";
    private static final String FILE_PRESET = "preset.md";
    private static final String FILE_CHARACTER_SETTINGS = "character_settings.json";
    private static final String FILE_SELECT_FORMAT = "select_format.md";
    private static final String FILE_GLOSSARY = "glossary.json";

    /** 模板模式固定的输出目录（不占用角色 id 命名空间） */
    private static final String TEMPLATE_DIR = "template";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;
    private final SessionFileManager sessionFileManager;
    private final AISettings chatAiSettings;
    private final UserSettings userSettings;

    public GetAuthoringCharacterTool(ObjectMapper objectMapper,
                                     CharacterInfoService characterInfoService,
                                     SessionFileManager sessionFileManager,
                                     CharacterGlossaryService glossaryService,
                                     @Qualifier(AISettings.CHAT) AISettings chatAiSettings,
                                     UserSettings userSettings) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.sessionFileManager = sessionFileManager;
        this.glossaryService = glossaryService;
        this.chatAiSettings = chatAiSettings;
        this.userSettings = userSettings;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        查看角色 / 生成角色卡文件。
                        不传参数时列出全部角色（id、名称、设定摘要）；
                        传入 id 时把该角色的角色卡摊成一组文件，写到当前会话文件目录下的 {角色ID}/ 中并返回文件清单，
                        每次调用都以数据库当前值覆盖重建：setting.md（角色设定）、preset.md（预设）、
                        character_settings.json（基础设置 / 模型参数 / 工具开关 / 快捷选项开关）、
                        select_format.md（快捷选项风格指导）、
                        glossary.json（角色词条，JSON 数组，每项只含 keyword / desc / content）。
                        传入 template=true 时不查角色，固定生成一份空白模板到 template/ 下，
                        其中 character_settings.json 预填默认值：mainColor / opacity 取用户设置、模型参数取主对话 AI，
                        供填写后用 character_authoring_upsert_tool 新建角色。
                        返回的路径可直接交给文件读写、编辑工具使用。""")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "string",
                                "角色 id，可省略。省略时列出全部角色；与 template=true 同时传时以 template 为准" +
                                        "（会忽略 id 只生成空白模板，并在结果里提示）。"),
                        new ToolRegister.Parameters("template", "boolean",
                                "为 true 时生成空白角色卡模板而非导出角色，可省略（默认 false）。")
                ));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        if (Boolean.TRUE.equals(arguments.getTemplate())) {
            String warning = "";
            if (StringUtils.hasText(arguments.getId())) {
                warning = "已忽略 id=" + arguments.getId().trim()
                        + "：template=true 只生成空白模板，不导出该角色；模板会覆盖会话目录下的 template/。\n\n";
            }
            return new ToolExecutor.ToolExecuteResponse(NAME, warning + exportTemplate(requireSessionId(context)));
        }

        if (!StringUtils.hasText(arguments.getId())) {
            return new ToolExecutor.ToolExecuteResponse(NAME, listAll());
        }

        CharacterInfo character = characterInfoService.findById(arguments.getId().trim());
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("未找到角色（id=" + arguments.getId() + "）");
        }

        return new ToolExecutor.ToolExecuteResponse(NAME, exportCard(requireSessionId(context), character));
    }

    /** 取当前会话 ID；写会话文件目录必须具备，缺失即报错 */
    private String requireSessionId(Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        if (chatSession == null || !StringUtils.hasText(chatSession.getId())) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话信息，无法写入会话文件目录");
        }
        return chatSession.getId();
    }

    // ==================== 角色卡文件导出 ====================

    /** 把角色摊成五个文件写进会话文件目录的 {角色ID}/ 下，返回目录与文件清单 */
    private String exportCard(String sessionId, CharacterInfo character) throws ToolExecutor.ToolExecuteException {
        String dir = SessionFileManager.sanitizeFileName(character.getId());
        try {
            sessionFileManager.writeSessionText(sessionId, dir + "/" + FILE_SETTING, blankToEmpty(character.getSetting()));
            sessionFileManager.writeSessionText(sessionId, dir + "/" + FILE_PRESET, blankToEmpty(character.getPreset()));
            sessionFileManager.writeSessionText(sessionId, dir + "/" + FILE_CHARACTER_SETTINGS, buildCharacterSettingsJson(character));
            sessionFileManager.writeSessionText(sessionId, dir + "/" + FILE_SELECT_FORMAT, buildSelectFormat(character));
            sessionFileManager.writeSessionText(sessionId, dir + "/" + FILE_GLOSSARY, buildGlossaryJson(character.getId()));
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("写入角色卡文件失败: " + e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已生成角色 `").append(character.getName()).append("`（id: `").append(character.getId())
          .append("`）的角色卡文件，目录：`").append(SessionFileManager.buildSessionMarker(dir)).append("/`\n\n");
        sb.append("- ").append(marker(dir, FILE_SETTING)).append(" — 角色设定\n");
        sb.append("- ").append(marker(dir, FILE_PRESET)).append(" — 预设\n");
        sb.append("- ").append(marker(dir, FILE_CHARACTER_SETTINGS)).append(" — 基础设置（名称 / 主题色 / 透明度 / 快捷选项开关 / 模型参数 / 工具开关）\n");
        sb.append("- ").append(marker(dir, FILE_SELECT_FORMAT)).append(" — 快捷选项风格指导\n");
        sb.append("- ").append(marker(dir, FILE_GLOSSARY)).append(" — 词条（JSON 数组：keyword / desc / content）\n");
        return sb.toString();
    }

    // ==================== 角色卡模板导出 ====================

    /** 生成空白角色卡模板到会话文件目录的 template/ 下，供填写后用 upsert 新建角色 */
    private String exportTemplate(String sessionId) throws ToolExecutor.ToolExecuteException {
        try {
            sessionFileManager.writeSessionText(sessionId, TEMPLATE_DIR + "/" + FILE_SETTING, "");
            sessionFileManager.writeSessionText(sessionId, TEMPLATE_DIR + "/" + FILE_PRESET, "");
            sessionFileManager.writeSessionText(sessionId, TEMPLATE_DIR + "/" + FILE_CHARACTER_SETTINGS, buildTemplateSettingsJson());
            sessionFileManager.writeSessionText(sessionId, TEMPLATE_DIR + "/" + FILE_SELECT_FORMAT, "");
            sessionFileManager.writeSessionText(sessionId, TEMPLATE_DIR + "/" + FILE_GLOSSARY, "[]");
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("写入角色卡模板失败: " + e.getMessage());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("已生成角色卡模板，目录：`").append(SessionFileManager.buildSessionMarker(TEMPLATE_DIR)).append("/`\n\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_SETTING)).append(" — 角色设定（待填写）\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_PRESET)).append(" — 预设（待填写）\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_CHARACTER_SETTINGS))
          .append(" — 基础设置（已预填：主题色 / 透明度取用户设置、模型参数取主对话 AI、工具开关默认关闭）\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_SELECT_FORMAT)).append(" — 快捷选项风格指导（待填写）\n");
        sb.append("- ").append(marker(TEMPLATE_DIR, FILE_GLOSSARY)).append(" — 词条（JSON 数组，待填写：keyword / desc / content）\n");
        sb.append("\n填写完成后，把五个文件的路径交给 character_authoring_upsert_tool 即可新建角色（name 必填）。");
        return sb.toString();
    }

    /** 模板的角色设置：预填用户主色 / 透明度与主对话模型参数，工具开关按两组名单列全并默认关闭 */
    private String buildTemplateSettingsJson() {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("id", "");
        settings.put("name", "");
        settings.put("mainColor", userSettings.getMainColor());
        settings.put("opacity", userSettings.getOpacity());
        Map<String, Object> chatSelect = new LinkedHashMap<>();
        chatSelect.put("enable", true);
        settings.put("chatSelect", chatSelect);
        settings.put("aiSettings", chatAiSettings);
        Map<String, Object> toolSettings = new LinkedHashMap<>();
        CharacterAuthoringToolKit.GLOSSARY_TOOLS.forEach(name -> toolSettings.put(name, false));
        CharacterAuthoringToolKit.GM_TOOLS.forEach(name -> toolSettings.put(name, false));
        settings.put("toolSettings", toolSettings);
        return pretty(settings);
    }

    /** 供模型使用的会话沙箱路径标记 */
    private String marker(String dir, String fileName) {
        return SessionFileManager.buildSessionMarker(dir + "/" + fileName);
    }

    /**
     * 角色设置：基础字段 + 模型参数 + 工具开关 + 快捷选项开关。
     * 只落轻量可编辑字段（头像 / 背景图不落文件，由用户在设置页维护）；快捷选项的风格指导另见 select_format.md。
     */
    private String buildCharacterSettingsJson(CharacterInfo character) {
        Map<String, Object> settings = new LinkedHashMap<>();
        settings.put("id", character.getId());
        settings.put("name", character.getName());
        settings.put("mainColor", character.getMainColor());
        settings.put("opacity", character.getOpacity());
        settings.put("chatSelect", buildChatSelect(character));
        settings.put("aiSettings", buildAiSettings(character));
        settings.put("toolSettings", parseJsonOrRaw(character.getTools()));
        return pretty(settings);
    }

    /** 快捷选项开关：chatSelect 去掉 format 之后的其余字段（通常只剩 enable） */
    private Object buildChatSelect(CharacterInfo character) {
        Object parsed = parseJsonOrRaw(character.getChatSelect());
        if (parsed instanceof ObjectNode objectNode) {
            objectNode.remove("format");
        }
        return parsed;
    }

    /** 模型参数：解析 ai_settings，再摘一次 prompt 作为存量兜底，防止残留设定文本 */
    private Object buildAiSettings(CharacterInfo character) {
        Object parsed = parseJsonOrRaw(character.getAiSettings());
        if (parsed instanceof ObjectNode objectNode) {
            objectNode.remove("prompt");
        }
        return parsed;
    }

    /** 快捷选项风格指导：chatSelect.format 的纯文本（内容不落 JSON） */
    private String buildSelectFormat(CharacterInfo character) {
        Object parsed = parseJsonOrRaw(character.getChatSelect());
        if (parsed instanceof ObjectNode objectNode) {
            JsonNode format = objectNode.get("format");
            if (format != null && !format.isNull()) {
                return format.asText();
            }
        }
        return "";
    }

    /**
     * 角色词条：按角色卡口径导出，每项只落 keyword / desc / content 三个业务字段，
     * id、characterId 与时间戳由服务端维护，不进文件（避免导出的 id 被误当成可往返的标识）。
     */
    private String buildGlossaryJson(String characterId) {
        List<CharacterGlossary> glossaries = glossaryService.listByCharacterId(characterId);
        if (glossaries == null || glossaries.isEmpty()) {
            return "[]";
        }
        List<GlossaryCardItem> items = glossaries.stream()
                .filter(glossary -> glossary != null)
                .map(glossary -> new GlossaryCardItem()
                        .setKeyword(glossary.getKeyword())
                        .setDesc(glossary.getDesc())
                        .setContent(glossary.getContent()))
                .toList();
        return pretty(items);
    }

    /** 不传参数时列出全部角色（id、名称、设定摘要） */
    private String listAll() {
        List<CharacterInfo> characters = characterInfoService.findAll();
        if (characters == null || characters.isEmpty()) {
            return "当前还没有任何角色。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(characters.size()).append(" 个角色：\n\n");
        for (CharacterInfo character : characters) {
            String setting = character.getSetting();
            String summary = StringUtils.hasText(setting) ? setting.replaceAll("\\s+", " ") : "(无设定)";
            if (summary.length() > 80) {
                summary = summary.substring(0, 80) + "…";
            }
            sb.append("- ").append(StringUtils.hasText(character.getName()) ? character.getName() : "(未命名)")
              .append("（id: `").append(character.getId()).append("`）：").append(summary).append("\n");
        }
        return sb.toString();
    }

    /** JSON 字符串解析成节点；空值返回空对象，解析失败回落到原始文本 */
    private Object parseJsonOrRaw(String json) {
        if (!StringUtils.hasText(json)) {
            return new LinkedHashMap<>();
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            return json;
        }
    }

    /** 美化输出 JSON */
    private String pretty(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private String blankToEmpty(String value) {
        return value == null ? "" : value;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    private static class Arguments {
        private String id;
        private Boolean template;
    }
}
