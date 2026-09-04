package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条修改工具 —— 按 id 修改已有词条的 keyword / desc / content
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/30
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
@ConditionalOnExpression("${plug.character.tool.glossary.enable:false} && ${plug.character.tool.glossary.update-glossary.enable:true}")
public class UpdateGlossaryTool implements ToolHandler {

    public static final String NAME = "character_glossary_update_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterGlossaryService glossaryService;

    public UpdateGlossaryTool(ObjectMapper objectMapper,
                              CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        修改已有词条。至少需要提供 keyword / desc / content 中的一项，未提供的字段保持不变。成功会返回更新后的词条信息。""")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "词条 ID（创建时返回的 id，或通过词条列表查询获得）"),
                        new ToolRegister.Parameters("keyword", "string", "新的关键词（可选，不提供则保持原值）"),
                        new ToolRegister.Parameters("desc", "string", "新的简短描述（可选，不提供则保持原值）"),
                        new ToolRegister.Parameters("content", "string", "新的完整内容（可选，不提供则保持原值）")
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

        if (arguments.getId() == null) {
            throw new ToolExecutor.ToolExecuteException("参数 id 不能为空");
        }
        if (!StringUtils.hasText(arguments.getKeyword())
                && !StringUtils.hasText(arguments.getDesc())
                && !StringUtils.hasText(arguments.getContent())) {
            throw new ToolExecutor.ToolExecuteException("至少需要提供 keyword / desc / content 中的一项");
        }

        // action 已声明 character 依赖（@ToolIncludeContext），此处直接取用
        CharacterInfo character = (CharacterInfo) context.get("character");

        // 查找词条
        CharacterGlossary existing = glossaryService.getById(arguments.getId());
        if (existing == null) {
            throw new ToolExecutor.ToolExecuteException("词条 id=" + arguments.getId() + " 不存在");
        }
        if (!character.getId().equals(existing.getCharacterId())) {
            throw new ToolExecutor.ToolExecuteException("词条 id=" + arguments.getId() + " 不属于当前角色，无权修改");
        }

        // 合并更新：只更新传入的非空字段
        if (StringUtils.hasText(arguments.getKeyword())) {
            existing.setKeyword(arguments.getKeyword().trim());
        }
        if (arguments.getDesc() != null) {
            existing.setDesc(arguments.getDesc().trim());
        }
        if (StringUtils.hasText(arguments.getContent())) {
            existing.setContent(arguments.getContent().trim());
        }

        try {
            CharacterGlossary updated = glossaryService.update(existing);
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "词条 `" + updated.getKeyword() + "`（id=" + updated.getId() + "）已成功更新。");
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
        private Long id;
        private String keyword;
        private String desc;
        private String content;
    }
}
