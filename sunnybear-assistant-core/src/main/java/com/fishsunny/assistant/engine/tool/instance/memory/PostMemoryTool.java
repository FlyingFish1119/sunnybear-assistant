package com.fishsunny.assistant.engine.tool.instance.memory;

/*
 * @Usage 核心记忆添加/修改工具 —— 支持 add 和 update 两种模式
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.MemoryToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.mvc.service.MemoryService;
import lombok.Data;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 核心记忆添加/修改工具
 * 通过 mode 参数切换：
 * - add：新增一条记忆，id 由系统自动生成
 * - update：修改已有记忆，需提供 id
 */
@ToolKitComponent(MemoryToolKit.class)
@ConditionalOnExpression("${engine.tool.memory.enable:true} && ${engine.tool.memory.post-memory.enable:true}")
public class PostMemoryTool implements ToolHandler {

    public static final String NAME = "post_memory_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;

    public PostMemoryTool(ObjectMapper objectMapper, MemoryService memoryService) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("添加或更新一条核心记忆。mode=add 新增，mode=update 修改已有记忆（需提供 id）。记忆会在后续对话中自动注入上下文。")
                .setRequired(List.of("mode", "content"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("mode", "string", "操作模式：add（新增）或 update（修改已有记忆）"),
                        new ToolRegister.Parameters("content", "string", "记忆内容。一句话一个事实，简洁独立。如'用户叫张三，是一名 Java 后端开发'"),
                        new ToolRegister.Parameters("id", "integer", "（update 模式必填）要修改的记忆 ID")
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

        try {
            MemoryRecord saved = memoryService.addOrUpdateMemory(arguments.getId(), arguments.getContent(), mode);
            String actionName = "add".equals(mode) ? "新增" : "修改";
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("记忆%s成功:\n  ID: %s\n  内容: %s", actionName, saved.getId(), saved.getContent()));
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("记忆操作失败: " + e.getMessage());
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
