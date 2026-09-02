package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条删除工具 —— 按 id 删除词条
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/30
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterGlossaryToolKit.class)
@ConditionalOnExpression("${plug.character.tool.glossary.enable:false} && ${plug.character.tool.glossary.delete-glossary.enable:true}")
public class DeleteGlossaryTool implements ToolHandler {

    public static final String NAME = "character_glossary_delete_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterGlossaryService glossaryService;

    public DeleteGlossaryTool(ObjectMapper objectMapper,
                              CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        删除指定 id 的词条。删除操作不可逆，请谨慎使用。""")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "要删除的词条 ID")
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

        if (arguments.getId() == null) {
            throw new ToolExecutor.ToolExecuteException("参数 id 不能为空");
        }

        CharacterInfo character = (CharacterInfo) context.get("character");
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前角色信息");
        }

        // 查找并校验所有权
        CharacterGlossary existing = glossaryService.getById(arguments.getId());
        if (existing == null) {
            throw new ToolExecutor.ToolExecuteException("词条 id=" + arguments.getId() + " 不存在");
        }
        if (!character.getId().equals(existing.getCharacterId())) {
            throw new ToolExecutor.ToolExecuteException("词条 id=" + arguments.getId() + " 不属于当前角色，无权删除");
        }

        String keyword = existing.getKeyword();
        try {
            glossaryService.deleteById(arguments.getId());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "词条 `" + keyword + "`（id=" + arguments.getId() + "）已成功删除。");
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
        private Long id;
    }
}
