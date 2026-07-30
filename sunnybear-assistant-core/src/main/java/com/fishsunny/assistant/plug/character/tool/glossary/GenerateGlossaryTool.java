package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条创建工具 —— 直接接收 keyword、desc、content 参数，创建并持久化词条
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterGlossaryToolKit.class)
@ConditionalOnExpression("${plug.character.tool.glossary.enable:false} && ${plug.character.tool.glossary.generate-glossary.enable:true}")
public class GenerateGlossaryTool implements ToolHandler {

    public static final String NAME = "character_glossary_create_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterGlossaryService glossaryService;

    public GenerateGlossaryTool(ObjectMapper objectMapper,
                                CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        为当前角色创建一条永久词条。用于记录新设定、事件、关系等信息。创建后可通过查询工具检索，也可用修改/删除工具维护。""")
                .setRequired(List.of("keyword", "content"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("keyword", "string",
                                "词条关键词，简洁明了（3-20字），用于精确查询和索引"),
                        new ToolRegister.Parameters("desc", "string",
                                "词条简短描述，一句话概括内容，便于快速预览。可选，不影响查询逻辑"),
                        new ToolRegister.Parameters("content", "string",
                                "词条完整内容，详细描述所有相关信息，支持 Markdown 格式")
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

        if (!StringUtils.hasText(arguments.getKeyword())) {
            throw new ToolExecutor.ToolExecuteException("参数 keyword 不能为空");
        }
        if (!StringUtils.hasText(arguments.getContent())) {
            throw new ToolExecutor.ToolExecuteException("参数 content 不能为空");
        }

        CharacterInfo character = (CharacterInfo) context.get("character");
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前角色信息");
        }

        // 检查是否已存在同名关键词
        CharacterGlossary existing = glossaryService.getByCharacterIdAndKeyword(
                character.getId(), arguments.getKeyword().trim());
        if (existing != null) {
            throw new ToolExecutor.ToolExecuteException(
                    "词条 `" + arguments.getKeyword().trim() + "` 已存在，请使用修改工具（character_glossary_update_tool）更新内容");
        }

        CharacterGlossary glossary = new CharacterGlossary()
                .setCharacterId(character.getId())
                .setKeyword(arguments.getKeyword().trim())
                .setDesc(StringUtils.hasText(arguments.getDesc()) ? arguments.getDesc().trim() : "")
                .setContent(arguments.getContent().trim());

        try {
            CharacterGlossary saved = glossaryService.create(glossary);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "词条 `" + saved.getKeyword() + "`（id=" + saved.getId() + "）已成功创建。");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("创建词条失败: " + e.getMessage());
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
        private String keyword;
        private String desc;
        private String content;
    }
}
