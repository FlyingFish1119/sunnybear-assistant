package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 保存角色设定 —— 存在则更新、不存在则新建（upsert）。
 *        更新为补丁式：只改传入字段，未传字段保持原值；人设写进 aiSettings.prompt，保留 adapter/model 等原字段。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterAuthoringToolKit.class)
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.upsert.enable:true}")
public class UpsertCharacterTool implements ToolHandler {

    public static final String NAME = "character_authoring_upsert_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;

    public UpsertCharacterTool(ObjectMapper objectMapper, CharacterInfoService characterInfoService) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        保存角色设定：用 id 或 name 定位，命中已有角色则更新，未命中则新建。
                        更新是补丁式的，只改传入的字段，未传字段保持原值；要清空某字段可传空字符串。
                        新建时必须提供 name 和 prompt。""")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "string", "目标角色 id，可省略。省略时用 name 定位。"),
                        new ToolRegister.Parameters("name", "string", "角色名称：定位已有角色；新建时作为新角色名称。"),
                        new ToolRegister.Parameters("newName", "string", "仅更新时使用：重命名后的新名称，可省略。"),
                        new ToolRegister.Parameters("prompt", "string", "人设提示词（系统提示词主体）。新建必填，更新时整体替换。"),
                        new ToolRegister.Parameters("preset", "string", "预设文本，每轮对话拼在人设之前，可省略。"),
                        new ToolRegister.Parameters("mainColor", "string", "主题色，如 #7C9CFF，可省略。"),
                        new ToolRegister.Parameters("opacity", "number", "背景透明度 0~1，可省略。"),
                        new ToolRegister.Parameters("glossaryTools", "boolean", "是否开启「角色词条」工具集（增删改查词条），可省略。"),
                        new ToolRegister.Parameters("gmTools", "boolean", "是否开启「GM 组件」工具集（数据库、骰子检定、战斗引擎），可省略。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        CharacterInfo existing = CharacterAuthoringSupport.find(
                characterInfoService, arguments.getId(), arguments.getName());
        return existing != null ? update(existing, arguments) : create(arguments);
    }

    private ToolExecutor.ToolExecuteResponse create(Arguments arguments) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(arguments.getName())) {
            throw new ToolExecutor.ToolExecuteException("新建角色时参数 name 不能为空");
        }
        if (!StringUtils.hasText(arguments.getPrompt())) {
            throw new ToolExecutor.ToolExecuteException("新建角色时参数 prompt 不能为空");
        }

        CharacterInfo character = new CharacterInfo()
                .setName(arguments.getName().trim())
                .setPreset(arguments.getPreset() == null ? "" : arguments.getPreset())
                .setMainColor(arguments.getMainColor() == null ? "" : arguments.getMainColor())
                .setOpacity(arguments.getOpacity());
        character.setAiSettings(CharacterAuthoringToolKit.writeJson(
                objectMapper, new AISettings().setPrompt(arguments.getPrompt()), "{}"));
        character.setTools(CharacterAuthoringToolKit.buildToolsJson(objectMapper,
                Boolean.TRUE.equals(arguments.getGlossaryTools()),
                Boolean.TRUE.equals(arguments.getGmTools())));

        try {
            CharacterInfo saved = characterInfoService.save(character);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已新建角色 `" + saved.getName() + "`（id: `" + saved.getId() + "`）。\n\n"
                            + CharacterAuthoringToolKit.describe(objectMapper, saved));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("新建角色失败: " + e.getMessage());
        }
    }

    private ToolExecutor.ToolExecuteResponse update(CharacterInfo character, Arguments arguments) throws ToolExecutor.ToolExecuteException {
        if (StringUtils.hasText(arguments.getNewName())) {
            character.setName(arguments.getNewName().trim());
        }
        if (arguments.getPreset() != null) {
            character.setPreset(arguments.getPreset());
        }
        if (arguments.getMainColor() != null) {
            character.setMainColor(arguments.getMainColor());
        }
        if (arguments.getOpacity() != null) {
            character.setOpacity(arguments.getOpacity());
        }
        if (arguments.getGlossaryTools() != null || arguments.getGmTools() != null) {
            Map<String, Boolean> current = CharacterAuthoringToolKit.parseToolsMap(objectMapper, character.getTools());
            boolean glossary = arguments.getGlossaryTools() != null
                    ? arguments.getGlossaryTools()
                    : CharacterAuthoringToolKit.groupEnabled(current, CharacterAuthoringToolKit.GLOSSARY_TOOLS);
            boolean gm = arguments.getGmTools() != null
                    ? arguments.getGmTools()
                    : CharacterAuthoringToolKit.groupEnabled(current, CharacterAuthoringToolKit.GM_TOOLS);
            character.setTools(CharacterAuthoringToolKit.buildToolsJson(objectMapper, glossary, gm));
        }
        if (arguments.getPrompt() != null) {
            AISettings ai = CharacterAuthoringToolKit.parseAiSettings(objectMapper, character);
            ai.setPrompt(arguments.getPrompt());
            character.setAiSettings(CharacterAuthoringToolKit.writeJson(objectMapper, ai, "{}"));
        }

        try {
            CharacterInfo updated = characterInfoService.update(character);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已更新角色 `" + updated.getName() + "`（id: `" + updated.getId() + "`）。\n\n"
                            + CharacterAuthoringToolKit.describe(objectMapper, updated));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("更新角色失败: " + e.getMessage());
        }
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
        private String name;
        private String newName;
        private String prompt;
        private String preset;
        private String mainColor;
        private Double opacity;
        private Boolean glossaryTools;
        private Boolean gmTools;
    }
}
