package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务删除工具 - 删除任务及其所有步骤
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 07:07
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.TaskToolKit;
import com.fishsunny.assistant.mvc.service.TaskService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-delete.enable:true}")
public class TaskDeleteTool implements ToolHandler {

    public static final String NAME = "task_delete_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskDeleteTool(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getId())) {
                throw new ToolExecutor.ToolExecuteException("缺少任务 ID");
            }

            Task deleted = taskService.deleteTask(arguments.getId());

            StringBuilder sb = new StringBuilder();
            sb.append("任务删除成功\n\n");
            sb.append("已删除的任务信息：\n");
            sb.append("- ID: ").append(deleted.getId()).append("\n");
            sb.append("- 名称: **").append(deleted.getTaskName()).append("**\n");
            sb.append("- 描述: ").append(deleted.getTaskDesc()).append("\n");
            sb.append("- 状态: ").append(deleted.getStatus()).append("\n");
            sb.append("- 创建时间: ").append(deleted.getCreateTime() != null
                    ? deleted.getCreateTime().format(FORMATTER) : "未知").append("\n");
            if (deleted.getFinishTime() != null) {
                sb.append("- 完成时间: ").append(deleted.getFinishTime().format(FORMATTER)).append("\n");
            }
            sb.append("\n注意：该任务下的所有步骤已一并删除。");

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("删除任务失败：" + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return new ToolRegister()
                .setName(NAME)
                .setDescription("删除任务及其所有步骤。不可逆，建议删除前先用 task_read_tool 确认任务内容。")
                .setRequired(List.of("id"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("id", "string", "要删除的任务 ID。删除前请仔细核对 ID 是否正确——该操作不可逆，建议先用 task_read_tool 确认任务内容后再删除")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String id;
    }
}
