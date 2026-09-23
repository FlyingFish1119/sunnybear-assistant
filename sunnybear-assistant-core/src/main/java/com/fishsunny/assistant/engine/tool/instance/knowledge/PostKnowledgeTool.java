package com.fishsunny.assistant.engine.tool.instance.knowledge;

/*
 * @Usage 知识库添加/修改工具 —— 支持 add 和 update 两种模式
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.KnowledgeToolKit;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 知识库添加/修改工具
 * 通过 mode 参数切换：
 * - add：新增一条知识条目，系统自动根据 content 生成 intro 简介并做 embedding 编码
 * - update：修改已有知识条目，需提供 id
 */
@Slf4j
@ToolKitComponent(KnowledgeToolKit.class)
@ConditionalOnExpression("${engine.tool.knowledge.enable:true} && ${engine.tool.knowledge.post-knowledge.enable:true}")
public class PostKnowledgeTool implements ToolHandler {

    public static final String NAME = "post_knowledge_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;

    public PostKnowledgeTool(ObjectMapper objectMapper,
                             KnowledgeService knowledgeService) {
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("添加或更新一条知识库条目。mode=add 新增，mode=update 修改已有条目（需提供 id）。知识会在相关提问时自动匹配注入上下文。")
                .setRequired(List.of("mode", "content"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("mode", "string", "操作模式：add（新增）或 update（修改已有条目）"),
                        new ToolRegister.Parameters("content", "string", "词条详细内容"),
                        new ToolRegister.Parameters("id", "integer", "（update 模式必填）要修改的条目 ID")
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

        if (!StringUtils.hasText(arguments.getMode())) {
            throw new ToolExecutor.ToolExecuteException("参数 mode 不能为空，请指定为 add 或 update");
        }
        if (!StringUtils.hasText(arguments.getContent())) {
            throw new ToolExecutor.ToolExecuteException("参数 content 不能为空");
        }

        String mode = arguments.getMode().trim().toLowerCase();
        String intro = knowledgeService.generateIntro(arguments.getContent());
        try {
            KnowledgeRecord saved = knowledgeService.addOrUpdateKnowledge(
                    arguments.getId(), intro, arguments.getContent(), mode);
            String actionName = "add".equals(mode) ? "新增" : "修改";
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("知识条目%s成功:\n  ID: %s\n  简介: %s\n  内容: %s",
                            actionName, saved.getId(), saved.getIntro(), saved.getContent()));
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("知识条目操作失败: " + e.getMessage());
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
        private String mode;
        private String content;
        private Integer id;
    }
}
