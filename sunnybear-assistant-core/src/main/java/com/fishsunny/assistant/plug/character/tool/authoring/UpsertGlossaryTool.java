package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 保存角色词条 —— 按 characterId + keyword 定位，存在则更新、不存在则新建（upsert）。
 *        更新是补丁式的，未传字段保持原值；新建时 content 必填。
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
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterAuthoringToolKit.class)
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.glossary-upsert.enable:true}")
public class UpsertGlossaryTool implements ToolHandler {

    public static final String NAME = "character_authoring_glossary_upsert_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;

    public UpsertGlossaryTool(ObjectMapper objectMapper,
                              CharacterInfoService characterInfoService,
                              CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        保存角色词条：按 characterId + keyword 定位，命中则更新、未命中则新建。
                        更新是补丁式的，只改传入字段；新建时 keyword 与 content 必填。
                        desc 是简短说明（会注入角色系统提示词），content 是完整内容（供角色工具查询）。""")
                .setRequired(List.of("characterId", "keyword"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("characterId", "string", "所属角色 id，必填。"),
                        new ToolRegister.Parameters("keyword", "string", "词条关键词（角色内唯一），必填。"),
                        new ToolRegister.Parameters("desc", "string", "简短描述，可省略。"),
                        new ToolRegister.Parameters("content", "string", "完整内容，新建必填，更新时可省略。")
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
        if (!StringUtils.hasText(arguments.getCharacterId())) {
            throw new ToolExecutor.ToolExecuteException("参数 characterId 不能为空");
        }
        if (!StringUtils.hasText(arguments.getKeyword())) {
            throw new ToolExecutor.ToolExecuteException("参数 keyword 不能为空");
        }

        CharacterInfo character = characterInfoService.findById(arguments.getCharacterId().trim());
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("未找到角色（id=" + arguments.getCharacterId() + "）");
        }

        String keyword = arguments.getKeyword().trim();
        CharacterGlossary existing = glossaryService.getByCharacterIdAndKeyword(character.getId(), keyword);
        return existing != null ? update(existing, arguments) : create(character, keyword, arguments);
    }

    private ToolExecutor.ToolExecuteResponse create(CharacterInfo character, String keyword, Arguments arguments) throws ToolExecutor.ToolExecuteException {
        if (!StringUtils.hasText(arguments.getContent())) {
            throw new ToolExecutor.ToolExecuteException("新建词条时参数 content 不能为空");
        }
        CharacterGlossary glossary = new CharacterGlossary()
                .setCharacterId(character.getId())
                .setKeyword(keyword)
                .setDesc(arguments.getDesc() == null ? "" : arguments.getDesc())
                .setContent(arguments.getContent());
        try {
            CharacterGlossary saved = glossaryService.create(glossary);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已为角色 `" + character.getName() + "` 新建词条。\n\n"
                            + CharacterAuthoringToolKit.describeGlossaryDetail(saved));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("新建词条失败: " + e.getMessage());
        }
    }

    private ToolExecutor.ToolExecuteResponse update(CharacterGlossary existing, Arguments arguments) throws ToolExecutor.ToolExecuteException {
        CharacterGlossary toSave = new CharacterGlossary()
                .setId(existing.getId())
                .setKeyword(existing.getKeyword())
                .setDesc(arguments.getDesc() != null ? arguments.getDesc() : existing.getDesc())
                .setContent(arguments.getContent() != null ? arguments.getContent() : existing.getContent());
        try {
            CharacterGlossary saved = glossaryService.update(toSave);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已更新词条 `" + saved.getKeyword() + "`。\n\n"
                            + CharacterAuthoringToolKit.describeGlossaryDetail(saved));
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("更新词条失败: " + e.getMessage());
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
        private String characterId;
        private String keyword;
        private String desc;
        private String content;
    }
}
