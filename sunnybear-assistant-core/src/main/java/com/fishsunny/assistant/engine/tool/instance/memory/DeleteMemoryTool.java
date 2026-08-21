package com.fishsunny.assistant.engine.tool.instance.memory;

/*
 * @Usage 核心记忆删除工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.MemoryToolKit;
import com.fishsunny.assistant.mvc.service.MemoryService;
import lombok.Data;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import java.util.List;
import java.util.Map;

/**
 * 核心记忆删除工具
 * 根据 ID 删除一条核心记忆
 */
@ToolKitComponent(MemoryToolKit.class)
@ConditionalOnExpression("${engine.tool.memory.enable:true} && ${engine.tool.memory.delete-memory.enable:true}")
public class DeleteMemoryTool implements ToolHandler {

    public static final String NAME = "delete_memory_tool";

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final MemoryService memoryService;

    public DeleteMemoryTool(ObjectMapper objectMapper, MemoryService memoryService) {
        this.objectMapper = objectMapper;
        this.memoryService = memoryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("根据 ID 删除一条核心记忆。删除不可恢复，请先确认 ID 正确。")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "integer", "要删除的记忆 ID。操作不可逆，请仔细核对")
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
            MemoryRecord deleted = memoryService.deleteMemory(arguments.getId());
            if (deleted == null) {
                return new ToolExecutor.ToolExecuteResponse(name(),
                        "未找到 ID 为 " + arguments.getId() + " 的记忆，可能已经被删除");
            }
            return new ToolExecutor.ToolExecuteResponse(name(),
                    String.format("记忆删除成功:\n  ID: %s\n  原内容: %s", deleted.getId(), deleted.getContent()));
        } catch (IllegalArgumentException e) {
            throw new ToolExecutor.ToolExecuteException(e.getMessage());
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("记忆删除失败: " + e.getMessage());
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
