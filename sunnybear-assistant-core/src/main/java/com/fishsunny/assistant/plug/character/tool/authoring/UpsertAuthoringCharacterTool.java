package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 保存角色设定 —— 从「角色卡文件」读入，存在则更新、不存在则新建（upsert）。
 *        入参为文件路径（一个 JSON + 三个 Markdown + 一个词条 JSON），与 GetAuthoringCharacterTool 产出的
 *        character_settings.json / setting.md / preset.md / select_format.md / glossary.json 一一对应，
 *        构成「get 摊开 → 编辑 → upsert 写回」的闭环。
 *        三个 Markdown 与词条文件可省略：某个未提供或文件不存在时，其对应内容保持原值、不修改，其余照常写入；
 *        词条文件提供了就按它重建该角色的词条库（先清空再导入，文件即权威快照）；
 *        词条只取 keyword / desc / content 三个业务字段，id 与时间戳不进文件、写了也忽略（见 GlossaryCardItem）。
 *        character_settings.json 必填，定位信息（id / name）与其余字段都由它提供。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.core.type.TypeReference;
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
import com.fishsunny.assistant.plug.character.dto.GlossaryImportResult;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterAuthoringToolKit.class)
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.upsert.enable:true}")
public class UpsertAuthoringCharacterTool implements ToolHandler {

    public static final String NAME = "character_authoring_upsert_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;
    private final SessionFileManager sessionFileManager;
    private final AISettings chatAiSettings;
    private final UserSettings userSettings;

    public UpsertAuthoringCharacterTool(ObjectMapper objectMapper,
                                        CharacterInfoService characterInfoService,
                                        CharacterGlossaryService glossaryService,
                                        SessionFileManager sessionFileManager,
                                        @Qualifier(AISettings.CHAT) AISettings chatAiSettings,
                                        UserSettings userSettings) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.glossaryService = glossaryService;
        this.sessionFileManager = sessionFileManager;
        this.chatAiSettings = chatAiSettings;
        this.userSettings = userSettings;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        保存角色设定：读取角色卡文件写回角色，用 id 或 name 定位，命中已有角色则更新，未命中则新建。
                        定位规则：character_settings.json 里 id 有值时就只按 id 查找，查不到会报错（不再静默新建），
                        想新建角色请把 id 留空或删掉该字段；id 为空时按 name（忽略大小写）匹配，仍未命中才新建。
                        入参为五个文件路径，与 character_authoring_get_tool 生成的角色卡一一对应：
                        character_settings.json（必填，提供定位信息与其余字段）、
                        setting.md（角色设定）、preset.md（预设）、select_format.md（快捷选项风格指导）、
                        glossary.json（角色词条，JSON 数组，每项只含 keyword / desc / content）。
                        三个 Markdown 均可省略：某个未提供或文件不存在时，其对应字段保持原值、不做修改，其余照常写入；
                        若提供了路径但文件不存在，会在返回结果末尾明确列出未生效的项。
                        新建时缺省字段有兜底：mainColor / opacity 取用户设置，aiSettings 取主对话 AI，
                        chatSelect 默认开启；toolSettings 只接收本工具集两组名单内的工具名，名单外的键会被丢弃。
                        词条文件省略或不存在时不动作；提供了就按文件内容重建该角色的词条库
                        （先清空该角色全部词条，再照文件全量导入，文件即权威快照，文件里没有的词条会被删除，
                        传空数组即清空全部词条）。词条每项只取 keyword / desc / content，
                        id 与时间戳由服务端生成，文件里写了也会被忽略。
                        词条重建的回报以数据库实际落库条数为准，并说明重复覆盖与被跳过的条数。
                        注意「缺失」与「空」语义不同：文件缺失 = 不修改；文件存在但内容为空 = 清空该字段。""")
                .setRequired(List.of("characterSettingsPath"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("characterSettingsPath", "string",
                                "character_settings.json 的路径，必填。会话沙箱写成 sessionId:相对路径，" +
                                        "如 sessionId:{角色ID}/character_settings.json；文件不存在或不是合法 JSON 对象时报错。"),
                        new ToolRegister.Parameters("settingPath", "string",
                                "setting.md 的路径，可省略。省略或文件不存在时角色设定保持原值。"),
                        new ToolRegister.Parameters("presetPath", "string",
                                "preset.md 的路径，可省略。省略或文件不存在时预设保持原值。"),
                        new ToolRegister.Parameters("selectFormatPath", "string",
                                "select_format.md 的路径，可省略。省略或文件不存在时快捷选项风格指导保持原值。"),
                        new ToolRegister.Parameters("glossaryPath", "string",
                                "glossary.json 的路径，可省略。省略或文件不存在时不动作；文件须为 JSON 数组，" +
                                        "每项只需 keyword / desc / content，会按其内容重建词条库（先清空再导入，空数组即清空全部词条）。")
                ));
    }

    @Override
    @ToolIncludeContext(key = "chatSession", type = ChatSession.class)
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession == null ? null : chatSession.getId();

        // 收集「提供了路径但文件不存在」的项，成功返回时统一提示（不改变「不修改」语义）
        List<String> missingHints = new ArrayList<>();

        // character_settings.json 必填：定位信息与其余字段都由它提供
        String settingsText = readIfPresent(arguments.getCharacterSettingsPath(), sessionId, null, missingHints);
        if (settingsText == null) {
            throw new ToolExecutor.ToolExecuteException(
                    "未找到角色设置文件（必填）: " + arguments.getCharacterSettingsPath());
        }
        ObjectNode settings = parseSettings(settingsText);

        // 三个 Markdown：null 表示未提供或文件不存在（不修改）；否则为文件全文（空串表示清空）
        String setting = readIfPresent(arguments.getSettingPath(), sessionId, "setting.md（角色设定）", missingHints);
        String preset = readIfPresent(arguments.getPresetPath(), sessionId, "preset.md（预设）", missingHints);
        String selectFormat = readIfPresent(arguments.getSelectFormatPath(), sessionId, "select_format.md（快捷选项风格指导）", missingHints);
        // 词条：null 表示未提供或文件不存在（不动作）；空列表表示按空快照重建（清空）
        List<CharacterGlossary> glossary = readGlossary(arguments.getGlossaryPath(), sessionId, missingHints);

        // 定位与新建/更新分流：id 有文本时只认 id（未命中不再静默新建）；id 为空时按 name 匹配
        String id = text(settings, "id");
        CharacterInfo existing;
        if (StringUtils.hasText(id)) {
            existing = characterInfoService.findById(id.trim());
            if (existing == null) {
                throw new ToolExecutor.ToolExecuteException(
                        "未找到角色（id=" + id.trim() + "）。若想新建角色，请把 character_settings.json 里的 id 留空或删掉该字段。");
            }
        } else {
            existing = CharacterAuthoringToolKit.find(characterInfoService, null, text(settings, "name"));
        }
        return existing != null
                ? update(existing, settings, setting, preset, selectFormat, glossary, missingHints)
                : create(settings, setting, preset, selectFormat, glossary, missingHints);
    }

    // ==================== 新建 / 更新 ====================

    private ToolExecutor.ToolExecuteResponse create(ObjectNode settings, String setting, String preset,
                                                    String selectFormat, List<CharacterGlossary> glossary,
                                                    List<String> missingHints)
            throws ToolExecutor.ToolExecuteException {
        String name = text(settings, "name");
        if (!StringUtils.hasText(name)) {
            throw new ToolExecutor.ToolExecuteException("新建角色时角色设置文件里的 name 不能为空");
        }

        CharacterInfo character = new CharacterInfo()
                .setName(name.trim())
                .setSetting(setting == null ? "" : setting)
                .setPreset(preset == null ? "" : preset)
                .setMainColor(defaultMainColor(settings))
                .setOpacity(defaultOpacity(settings));
        character.setAiSettings(defaultAiSettings(settings));
        character.setTools(filterTools(settings.get("toolSettings")).toString());
        ObjectNode chatSelect = defaultChatSelect(settings, selectFormat);
        character.setChatSelect(chatSelect.toString());

        try {
            CharacterInfo saved = characterInfoService.save(character);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已新建角色 `" + saved.getName() + "`（id: `" + saved.getId() + "`）。\n\n"
                            + CharacterAuthoringToolKit.describe(objectMapper, saved)
                            + rebuildGlossary(saved.getId(), glossary)
                            + missingPathHint(missingHints));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("新建角色失败: " + e.getMessage());
        }
    }

    private ToolExecutor.ToolExecuteResponse update(CharacterInfo character, ObjectNode settings, String setting,
                                                    String preset, String selectFormat, List<CharacterGlossary> glossary,
                                                    List<String> missingHints)
            throws ToolExecutor.ToolExecuteException {
        // name 非空才写：既避免把角色名清空，也让「用 id 定位 + name 不同」承担改名职责
        String name = text(settings, "name");
        if (StringUtils.hasText(name)) {
            character.setName(name.trim());
        }
        if (setting != null) {
            character.setSetting(setting);
        }
        if (preset != null) {
            character.setPreset(preset);
        }
        // 标量字段：出现即为意图，JSON null 视为清空
        if (settings.has("mainColor")) {
            character.setMainColor(text(settings, "mainColor"));
        }
        if (settings.has("opacity")) {
            character.setOpacity(number(settings, "opacity"));
        }
        ObjectNode chatSelect = mergeChatSelect(character, settings, selectFormat);
        if (chatSelect != null) {
            character.setChatSelect(chatSelect.toString());
        }
        // 复合字段：为 null 没有意义，视为未提供、保持原值
        if (settings.hasNonNull("aiSettings")) {
            character.setAiSettings(settings.get("aiSettings").toString());
        }
        if (settings.hasNonNull("toolSettings")) {
            character.setTools(filterTools(settings.get("toolSettings")).toString());
        }

        try {
            CharacterInfo updated = characterInfoService.update(character);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已更新角色 `" + updated.getName() + "`（id: `" + updated.getId() + "`）。\n\n"
                            + CharacterAuthoringToolKit.describe(objectMapper, updated)
                            + rebuildGlossary(updated.getId(), glossary)
                            + missingPathHint(missingHints));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("更新角色失败: " + e.getMessage());
        }
    }

    // ==================== 新建默认值兜底 ====================

    /** 主题色：缺失或为空时回落用户设置的主色（与模板口径一致） */
    private String defaultMainColor(ObjectNode settings) {
        String mainColor = text(settings, "mainColor");
        return StringUtils.hasText(mainColor) ? mainColor : userSettings.getMainColor();
    }

    /** 透明度：缺失或非数字时回落用户设置的透明度 */
    private Double defaultOpacity(ObjectNode settings) {
        Double opacity = number(settings, "opacity");
        return opacity != null ? opacity : userSettings.getOpacity();
    }

    /** 模型参数：缺失 / null / 空对象时回落注入的主对话 AISettings */
    private String defaultAiSettings(ObjectNode settings) {
        JsonNode aiSettings = settings.get("aiSettings");
        if (aiSettings != null && aiSettings.isObject() && !aiSettings.isEmpty()) {
            return aiSettings.toString();
        }
        try {
            return objectMapper.writeValueAsString(chatAiSettings);
        } catch (Exception e) {
            return "{}";
        }
    }

    /** 快捷选项开关：缺失时默认开启（与模板一致），不留空对象；风格指导另按 select_format.md 回填 */
    private ObjectNode defaultChatSelect(ObjectNode settings, String selectFormat) {
        ObjectNode base = mergeChatSelect(null, settings, selectFormat);
        if (base == null) {
            base = objectMapper.createObjectNode();
        }
        if (!base.has("enable")) {
            base.put("enable", true);
        }
        return base;
    }

    // ==================== toolSettings 白名单过滤 ====================

    /**
     * 按角色编写工具集的两组名单过滤 toolSettings：名单内的键保留，名单外的键丢弃，
     * 值统一按布尔处理（非布尔或 null 视为 false），与「不接收任意工具名」的口径保持一致。
     */
    private ObjectNode filterTools(JsonNode toolSettings) {
        ObjectNode filtered = objectMapper.createObjectNode();
        if (toolSettings == null || !toolSettings.isObject()) {
            return filtered;
        }
        for (String toolName : CharacterAuthoringToolKit.allToolNames()) {
            JsonNode value = toolSettings.get(toolName);
            filtered.put(toolName, value != null && value.isBoolean() && value.asBoolean());
        }
        return filtered;
    }

    // ==================== 缺失路径提示 ====================

    /** 把「提供了路径但文件不存在」的项拼成一段提示；没有则返回空串 */
    private String missingPathHint(List<String> missingHints) {
        if (missingHints == null || missingHints.isEmpty()) {
            return "";
        }
        return "\n\n⚠ 以下路径的文件不存在，对应字段未做修改：" + String.join("；", missingHints);
    }

    // ==================== chatSelect 合并 ====================

    /**
     * 合并 chatSelect。角色设置文件里存的是「摘掉 format 的残壳」（风格指导单独在 select_format.md），
     * 因此以文件里的 chatSelect 为基础，再按需回填 format：select_format.md 提供时覆盖 format；
     * 未提供时沿用角色现有 format，避免被残壳抹掉。
     *
     * @param existing 更新时的角色现有值；新建传 null
     * @return 合并后的 chatSelect；两边都没有可用信息时返回 null（不修改该字段）
     */
    private ObjectNode mergeChatSelect(CharacterInfo existing, ObjectNode settings, String selectFormat) {
        boolean hasChatSelect = settings.has("chatSelect") && settings.get("chatSelect").isObject();
        if (!hasChatSelect && selectFormat == null) {
            return null;
        }
        ObjectNode base;
        if (hasChatSelect) {
            base = ((ObjectNode) settings.get("chatSelect")).deepCopy();
            if (selectFormat == null && !base.has("format")) {
                String current = currentFormat(existing);
                if (current != null) {
                    base.put("format", current);
                }
            }
        } else {
            base = parseObject(existing == null ? null : existing.getChatSelect());
        }
        if (selectFormat != null) {
            base.put("format", selectFormat);
        }
        return base;
    }

    /** 取角色现有 chatSelect.format 的文本；没有则返回 null */
    private String currentFormat(CharacterInfo character) {
        if (character == null) {
            return null;
        }
        JsonNode format = parseObject(character.getChatSelect()).get("format");
        return format == null || format.isNull() ? null : format.asText();
    }

    /** 把 JSON 字符串解析成对象节点；空值或解析失败时返回空对象 */
    private ObjectNode parseObject(String json) {
        if (!StringUtils.hasText(json)) {
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            return node != null && node.isObject() ? (ObjectNode) node : objectMapper.createObjectNode();
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    // ==================== 词条 ====================

    /**
     * 读取词条文件（JSON 数组）。每项只取 keyword / desc / content 三个业务字段，
     * id / characterId / 时间戳一律忽略（旧文件带着这些字段也能照样导入）。
     *
     * @return 未提供或文件不存在时返回 null；否则为词条列表（空文件 / 空数组视为空列表 = 空快照）
     */
    private List<CharacterGlossary> readGlossary(String path, String sessionId, List<String> missingHints)
            throws ToolExecutor.ToolExecuteException {
        String text = readIfPresent(path, sessionId, "glossary.json（角色词条）", missingHints);
        if (text == null) {
            return null;
        }
        if (!StringUtils.hasText(text)) {
            return List.of();
        }
        List<GlossaryCardItem> items;
        try {
            items = objectMapper.readValue(text, new TypeReference<List<GlossaryCardItem>>() {
            });
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException(
                    "词条文件不是合法的 JSON 数组，每项需包含 keyword / desc / content");
        }
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        List<CharacterGlossary> glossaries = new ArrayList<>();
        for (GlossaryCardItem item : items) {
            if (item == null) {
                continue;
            }
            glossaries.add(new CharacterGlossary()
                    .setKeyword(item.getKeyword())
                    .setDesc(item.getDesc())
                    .setContent(item.getContent()));
        }
        return glossaries;
    }

    /**
     * 按词条文件重建角色的词条库：走 CharacterGlossaryService#rebuildByCharacterId，
     * 先清空该角色现有词条，再照文件全量导入（文件即权威快照，没有的词条会被删除）。
     *
     * @return 未提供文件（null）时返回空串；重建失败时返回一段说明而非抛异常（角色本身已写入，不必整体回滚）
     * 回报口径取数据库实际落库条数；只有确实发生覆盖（同关键词重复）或跳过时才补括号说明，避免回报虚高。
     */
    private String rebuildGlossary(String characterId, List<CharacterGlossary> items) {
        if (items == null) {
            return "";
        }
        try {
            GlossaryImportResult result = glossaryService.rebuildByCharacterId(characterId, items);
            List<CharacterGlossary> landed = glossaryService.listByCharacterId(characterId);
            int actual = landed == null ? 0 : landed.size();
            String text = "\n\n词条重建：实际落库 " + actual + " 条";
            if (result.getUpdated() > 0 || result.getFailed() > 0) {
                List<String> notes = new ArrayList<>();
                if (result.getUpdated() > 0) {
                    notes.add("覆盖了 " + result.getUpdated() + " 条重复关键词");
                }
                if (result.getFailed() > 0) {
                    notes.add("另有 " + result.getFailed() + " 条缺少关键词或内容，未导入");
                }
                text += "（" + String.join("；", notes) + "）";
            }
            return text;
        } catch (Exception e) {
            return "\n\n词条重建失败：" + e.getMessage();
        }
    }

    // ==================== 读取辅助 ====================

    /** 解析角色设置文件为 JSON 对象 */
    private ObjectNode parseSettings(String text) throws ToolExecutor.ToolExecuteException {
        try {
            JsonNode node = objectMapper.readTree(text);
            if (node == null || !node.isObject()) {
                throw new ToolExecutor.ToolExecuteException("角色设置文件必须是一个 JSON 对象");
            }
            return (ObjectNode) node;
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("角色设置文件不是合法 JSON: " + e.getMessage());
        }
    }

    /**
     * 读取文件全文。
     *
     * @param usageLabel 该路径对应用途的展示名（如「setting.md（角色设定）」）；为 null 表示必填项，缺失不记提示
     * @param missingHints 收集「提供了路径但文件不存在」的项，成功返回时统一提示
     * @return 路径为空（未提供）或文件不存在时返回 null；否则为文件内容（可能为空串）
     */
    private String readIfPresent(String path, String sessionId, String usageLabel, List<String> missingHints)
            throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(path)) {
            return null;
        }
        Path filePath;
        try {
            filePath = sessionFileManager.resolveToolPath(path.trim(), sessionId).path();
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("路径不合法: " + path + "（" + e.getMessage() + "）");
        }
        if (!Files.isRegularFile(filePath)) {
            if (usageLabel != null) {
                missingHints.add(usageLabel + " " + path.trim());
            }
            return null;
        }
        try {
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ToolExecutor.ToolExecuteException("读取文件失败: " + path + "（" + e.getMessage() + "）");
        }
    }

    /** 取字符串字段；字段不存在或为 JSON null 时返回 null */
    private String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 取数字字段；字段不存在、为 null 或非数字时返回 null */
    private Double number(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isNumber() ? value.asDouble() : null;
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
        private String characterSettingsPath;
        private String settingPath;
        private String presetPath;
        private String selectFormatPath;
        private String glossaryPath;
    }
}
