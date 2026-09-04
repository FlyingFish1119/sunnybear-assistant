package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条查询工具 —— AI 通过 keyword 查询词条，返回 Markdown 格式的关键词、描述和完整内容，要求 AI 遇到相关话题主动查询
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterGlossaryToolKit.class)
@ConditionalOnExpression("${plug.character.tool.glossary.enable:false} && ${plug.character.tool.glossary.query-glossary.enable:true}")
public class QueryGlossaryTool implements ToolHandler {

    public static final String NAME = "character_glossary_query_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterGlossaryService glossaryService;

    public QueryGlossaryTool(ObjectMapper objectMapper,
                              CharacterGlossaryService glossaryService
                              ) {
        this.objectMapper = objectMapper;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        查询当前角色的词条内容。涉及角色设定时主动调用此工具获取准确数据，禁止在未查询时捏造。""")
                .setRequired(List.of("keyword"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("keyword", "string", "要查询的词条关键词，精确匹配。例如角色名、地名、组织名、术语等。")
                ));
    }

    @Override
    @ToolIncludeContext(key = "character", type = CharacterInfo.class)
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

        // action 已声明 character 依赖（@ToolIncludeContext），此处直接取用
        CharacterInfo characterInfo = (CharacterInfo) context.get("character");

        CharacterGlossary glossary = glossaryService.getByCharacterIdAndKeyword(
                characterInfo.getId(), arguments.getKeyword().trim());
        if (glossary == null) {
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "当前角色没有关键词为 `" + arguments.getKeyword().trim() + "` 的词条。");
        }

        StringBuilder result = new StringBuilder();
        result.append("## ").append(glossary.getKeyword()).append("\n\n");
        if (StringUtils.hasText(glossary.getDesc())) {
            result.append("> ").append(glossary.getDesc()).append("\n\n");
        }
        result.append(glossary.getContent());
        return new ToolExecutor.ToolExecuteResponse(NAME, result.toString());
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
    }
}
