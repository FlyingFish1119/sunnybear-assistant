package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 删除角色词条 —— 按 characterId + keyword 定位，直接删除，无需用户确认。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
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
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.glossary-delete.enable:true}")
public class DeleteAuthoringGlossaryTool implements ToolHandler {

    public static final String NAME = "character_authoring_glossary_delete_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;

    public DeleteAuthoringGlossaryTool(ObjectMapper objectMapper,
                              CharacterInfoService characterInfoService,
                              CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("按 characterId + keyword 删除角色词条，无需确认。")
                .setRequired(List.of("characterId", "keyword"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("characterId", "string", "所属角色 id，必填。"),
                        new ToolRegister.Parameters("keyword", "string", "要删除的词条关键词，必填。")
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
        if (existing == null) {
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "角色 `" + character.getName() + "` 下没有关键词为 `" + keyword + "` 的词条。");
        }

        try {
            glossaryService.deleteById(existing.getId());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "已删除角色 `" + character.getName() + "` 的词条 `" + keyword + "`。");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("删除词条失败: " + e.getMessage());
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
    }
}
