package com.fishsunny.assistant.engine.tool.instance.knowledge;

/*
 * @Usage 知识库删除工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
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
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

/**
 * 知识库删除工具
 * 根据 ID 删除一条知识条目
 */
@ToolKitComponent(KnowledgeToolKit.class)
@ConditionalOnExpression("${engine.tool.knowledge.enable:true} && ${engine.tool.knowledge.delete-knowledge.enable:true}")
public class DeleteKnowledgeTool implements ToolHandler {

    public static final String NAME = "delete_knowledge_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final KnowledgeService knowledgeService;

    public DeleteKnowledgeTool(ObjectMapper objectMapper, KnowledgeService knowledgeService) {
        this.objectMapper = objectMapper;
        this.knowledgeService = knowledgeService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("根据 ID 删除一条知识库条目。删除不可恢复，请先确认 ID 正确。")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "要删除的条目 ID。操作不可逆，请仔细核对")
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

        try {
            KnowledgeRecord deleted = knowledgeService.deleteKnowledge(arguments.getId());
            if (deleted == null) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "未找到 ID 为 " + arguments.getId() + " 的知识条目，可能已经被删除");
            }
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("知识条目删除成功:\n  ID: %s\n  原简介: %s\n  原内容: %s",
                            deleted.getId(), deleted.getIntro(), deleted.getContent()));
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("知识条目删除失败: " + e.getMessage());
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
