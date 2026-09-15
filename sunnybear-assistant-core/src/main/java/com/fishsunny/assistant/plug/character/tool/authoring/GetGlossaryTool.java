package com.fishsunny.assistant.plug.character.tool.authoring;

/*
 * @Usage 查看角色词条 —— 按 characterId + keyword 取单条（含完整内容）；
 *        只给 characterId 时按每页 10 条分页列出该角色全部词条（含完整内容）。
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
@ConditionalOnExpression("${plug.character.tool.authoring.enable:true} && ${plug.character.tool.authoring.glossary-get.enable:true}")
public class GetGlossaryTool implements ToolHandler {

    public static final String NAME = "character_authoring_glossary_get_tool";

    /** 分页固定每页条数 */
    private static final int PAGE_SIZE = 10;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;

    public GetGlossaryTool(ObjectMapper objectMapper,
                           CharacterInfoService characterInfoService,
                           CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.characterInfoService = characterInfoService;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        查看角色词条。给 characterId + keyword 返回该词条完整内容；
                        只给 characterId 则分页列出该角色全部词条（含完整内容），每页固定 10 条。""")
                .setRequired(List.of("characterId"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("characterId", "string", "角色 id，必填。"),
                        new ToolRegister.Parameters("keyword", "string", "词条关键词，可省略。给了就取该条完整内容。"),
                        new ToolRegister.Parameters("page", "integer", "页码，从 1 开始，仅列表时有效，可省略，默认 1。")
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

        CharacterInfo character = characterInfoService.findById(arguments.getCharacterId().trim());
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("未找到角色（id=" + arguments.getCharacterId() + "）");
        }

        if (StringUtils.hasText(arguments.getKeyword())) {
            CharacterGlossary glossary = glossaryService.getByCharacterIdAndKeyword(
                    character.getId(), arguments.getKeyword().trim());
            if (glossary == null) {
                throw new ToolExecutor.ToolExecuteException("角色 `" + character.getName() + "` 下没有关键词为 `"
                        + arguments.getKeyword().trim() + "` 的词条");
            }
            return new ToolExecutor.ToolExecuteResponse(NAME, CharacterAuthoringToolKit.describeGlossaryDetail(glossary));
        }

        return new ToolExecutor.ToolExecuteResponse(NAME, listPage(character, arguments.getPage()));
    }

    private String listPage(CharacterInfo character, Integer requestedPage) throws ToolExecutor.ToolExecuteException {
        List<CharacterGlossary> all = glossaryService.listByCharacterId(character.getId());
        if (all == null || all.isEmpty()) {
            return "角色 `" + character.getName() + "` 暂无词条。";
        }
        int total = all.size();
        int totalPages = (total + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = requestedPage == null || requestedPage < 1 ? 1 : requestedPage;
        if (page > totalPages) {
            throw new ToolExecutor.ToolExecuteException(
                    "页码超出范围：共 " + totalPages + " 页（" + total + " 条），请求的是第 " + page + " 页。");
        }
        int from = (page - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);
        List<CharacterGlossary> items = all.subList(from, to);

        return "角色 `" + character.getName() + "` 的词条（共 " + total + " 条，第 " + page + "/" + totalPages
                + " 页，每页 " + PAGE_SIZE + " 条）：\n\n"
                + CharacterAuthoringToolKit.describeGlossaryWithContent(items);
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
        private Integer page;
    }
}
