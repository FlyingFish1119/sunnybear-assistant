package com.fishsunny.assistant.engine.tool.instance.knowledge;

/*
 * @Usage 知识库查看工具 — 根据 ID 查看知识条目的完整详情
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.KnowledgeToolKit;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 知识库查看工具
 * 根据 ID 返回指定知识条目的完整详情（标题、完整内容、创建时间、更新时间）。
 */
@ToolKitComponent(KnowledgeToolKit.class)
@ConditionalOnExpression("${engine.tool.knowledge.enable:true} && ${engine.tool.knowledge.knowledge-read.enable:true}")
public class KnowledgeReadTool implements ToolHandler {

    public static final String NAME = "knowledge_read_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;

    @Autowired
    public KnowledgeReadTool(ObjectMapper objectMapper, KnowledgeService knowledgeService) {
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("根据 ID 查看知识条目的完整详情，包括标题、完整内容、创建时间和更新时间。ID 可从 knowledge_list_tool 的返回结果中获取。")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "要查看的知识条目 ID。可从 knowledge_list_tool 的返回结果中获取")
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
            throw new ToolExecutor.ToolExecuteException("缺少知识条目 ID");
        }

        try {
            KnowledgeRecord record = knowledgeService.getKnowledgeById(arguments.getId());
            if (record == null) {
                return new ToolExecutor.ToolExecuteResponse(name(), "未找到 ID 为 " + arguments.getId() + " 的知识条目");
            }

            StringBuilder sb = new StringBuilder();
            sb.append("知识条目详情\n\n");
            sb.append("- ID: ").append(record.getId()).append("\n");
            sb.append("- 标题: **").append(record.getTitle()).append("**\n");
            sb.append("- 内容: ").append(record.getContent() != null ? record.getContent() : "（无内容）").append("\n");
            sb.append("- 创建时间: ").append(record.getCreateTime() != null
                    ? record.getCreateTime().format(FORMATTER) : "未知").append("\n");
            sb.append("- 更新时间: ").append(record.getUpdateTime() != null
                    ? record.getUpdateTime().format(FORMATTER) : "未知").append("\n");

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("查看知识条目失败：" + e.getMessage());
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
        private Integer id;
    }
}