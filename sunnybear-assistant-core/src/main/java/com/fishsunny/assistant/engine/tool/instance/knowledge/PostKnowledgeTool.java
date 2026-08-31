package com.fishsunny.assistant.engine.tool.instance.knowledge;

/*
 * @Usage 知识库添加/修改工具 —— 支持 add 和 update 两种模式
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.KnowledgeToolKit;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    private final ChatHttpHandler chatHttpHandler;
    private final AISettings aiSettings;
    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;

    public PostKnowledgeTool(ObjectMapper objectMapper,
                             ChatHttpHandler chatHttpHandler,
                             @Qualifier(AISettings.CUB) AISettings aiSettings,
                             KnowledgeService knowledgeService) {
        this.objectMapper = objectMapper;
        this.chatHttpHandler = chatHttpHandler;
        this.aiSettings = aiSettings;
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
        String intro = generateIntro(arguments.getContent());
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

    private String generateIntro(String content) {
        String prompt = """
                你是一名知识库编辑。请根据下面的知识内容，写一段简洁的【简介】用于知识条目的检索与展示。

                要求：
                1. 约 50 个字左右，比标题内容更丰富，但远短于完整内容。
                2. 概括内容的核心主题与要点，便于后续语义匹配时命中。
                3. 直接输出简介文本，不要加引号、不要加任何前缀或解释。
                """;

        ChatRequest chatRequest = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(prompt),
                        new ChatMessage().user(content)
                        )
                );

        AtomicReference<String> intro = new AtomicReference<>();
        ChatHttpHandler.TranslateData translateData = new ChatHttpHandler.TranslateData(
                UUID.randomUUID().toString(),
                aiSettings.getAdapterName(),
                aiSettings.getStream(),
                chatRequest
        );
        ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler(null,
                ((result, lastRes) -> intro.set(result.content())));
        try {
            chatHttpHandler.translate(translateData, translateHandler);
        } catch (Exception e) {
            log.warn("Failed to generate intro: {}", e.getMessage());
            intro.set(content);
        }
        return intro.get();
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
