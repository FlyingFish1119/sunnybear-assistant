package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 查看角色 —— 不传参数列出全部角色；传入 id 或 name 返回该角色的完整设定。
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
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterAuthoringToolKit.class)
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.get.enable:true}")
public class GetCharacterTool implements ToolHandler {

    public static final String NAME = "character_authoring_get_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;

    public GetCharacterTool(ObjectMapper objectMapper,
                            CharacterInfoService characterInfoService,
                            CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        查看角色。不传任何参数时列出全部角色（id、名称、人设摘要）；
                        传入 id 或 name（二选一）时返回该角色的完整设定，并附带其词条列表（关键词、描述）。""")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "string", "角色 id，可省略。"),
                        new ToolRegister.Parameters("name", "string", "角色名称，可省略。")
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

        if (!StringUtils.hasText(arguments.getId()) && !StringUtils.hasText(arguments.getName())) {
            return new ToolExecutor.ToolExecuteResponse(NAME, listAll());
        }

        CharacterInfo character = CharacterAuthoringSupport.find(
                characterInfoService, arguments.getId(), arguments.getName());
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("未找到角色（id=" + arguments.getId() + ", name=" + arguments.getName() + "）");
        }
        return new ToolExecutor.ToolExecuteResponse(NAME,
                CharacterAuthoringToolKit.describe(objectMapper, character)
                        + "\n\n### 词条\n\n"
                        + CharacterAuthoringToolKit.describeGlossary(glossaryService, character));
    }

    private String listAll() {
        List<CharacterInfo> characters = characterInfoService.findAll();
        if (characters == null || characters.isEmpty()) {
            return "当前还没有任何角色。";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("共 ").append(characters.size()).append(" 个角色：\n\n");
        for (CharacterInfo character : characters) {
            AISettings ai = CharacterAuthoringToolKit.parseAiSettings(objectMapper, character);
            String prompt = ai.getPrompt();
            String summary = StringUtils.hasText(prompt)
                    ? prompt.replaceAll("\\s+", " ")
                    : "(无人设)";
            if (summary.length() > 80) {
                summary = summary.substring(0, 80) + "…";
            }
            sb.append("- ").append(StringUtils.hasText(character.getName()) ? character.getName() : "(未命名)")
              .append("（id: `").append(character.getId()).append("`）：").append(summary).append("\n");
        }
        return sb.toString();
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
    }
}
