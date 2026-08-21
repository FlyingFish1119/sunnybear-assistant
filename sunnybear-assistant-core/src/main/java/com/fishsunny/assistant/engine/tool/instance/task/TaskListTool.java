package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务列表工具 - 分页查看所有外层主任务（不含具体步骤）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 08:00
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

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-list.enable:true}")
public class TaskListTool implements ToolHandler {

    public static final String NAME = "task_list_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final int DEFAULT_LIMIT = 10;
    private static final int MAX_LIMIT = 50;

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskListTool(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            int limit = arguments.getLimit() != null ? arguments.getLimit() : DEFAULT_LIMIT;
            int offset = arguments.getOffset() != null ? arguments.getOffset() : 0;

            if (limit <= 0) {
                throw new ToolExecutor.ToolExecuteException("limit 必须大于 0");
            }
            if (limit > MAX_LIMIT) {
                throw new ToolExecutor.ToolExecuteException("limit 最大为 " + MAX_LIMIT);
            }
            if (offset < 0) {
                throw new ToolExecutor.ToolExecuteException("offset 不能为负数");
            }

            TaskService.TaskListResult result = taskService.selectTasks(limit, offset);
            List<Task> tasks = result.tasks();
            int total = result.total();

            StringBuilder sb = new StringBuilder();

            if (total == 0) {
                sb.append("当前没有任何任务。\n\n");
                sb.append("你可以使用 task_create_tool 创建新任务。");
            } else {
                int from = offset + 1;
                int to = Math.min(offset + tasks.size(), total);

                sb.append("任务列表（共 **").append(total).append("** 个任务");
                if (total > limit) {
                    sb.append("，当前显示第 **").append(from).append("** ~ **").append(to).append("** 个");
                }
                sb.append("）\n");

                for (int i = 0; i < tasks.size(); i++) {
                    Task task = tasks.get(i);
                    sb.append("\n---\n\n");
                    sb.append("**").append(from + i).append("**. **").append(task.getTaskName()).append("**");
                    sb.append("  `").append(statusLabel(task.getStatus())).append("`\n");
                    sb.append("- ID: `").append(task.getId()).append("`\n");
                    sb.append("- 描述: ").append(truncate(task.getTaskDesc(), 100)).append("\n");
                    sb.append("- 创建时间: ").append(task.getCreateTime() != null
                            ? task.getCreateTime().format(FORMATTER) : "未知").append("\n");
                    if (task.getFinishTime() != null) {
                        sb.append("- 完成时间: ").append(task.getFinishTime().format(FORMATTER)).append("\n");
                    }
                }

                if (total > limit) {
                    sb.append("\n---\n\n");
                    sb.append("💡 分页提示：当前 offset=").append(offset)
                            .append(", limit=").append(limit)
                            .append("。查看后续任务可将 offset 设为 ").append(offset + limit)
                            .append("。");
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("查询任务列表失败：" + e.getMessage());
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
                .setDescription("分页查询任务列表，按创建时间倒序。返回任务摘要（名称、状态、时间），不含具体步骤，需查看步骤详情请用 task_read_tool。")
                .setRequired(List.of())
                .setParameters(List.of(
                        new ToolRegister.Parameters("limit", "integer",
                                "每页返回的任务数量，默认 10，最大 50。例如 limit=10 表示返回最多 10 个任务"),
                        new ToolRegister.Parameters("offset", "integer",
                                "跳过的任务数，默认 0（从第一条开始）。例如 offset=10 表示跳过前 10 条记录，配合 limit 实现翻页")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private Integer limit;
        private Integer offset;
    }

    private static String statusLabel(String status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case Task.STATUS_WAITING -> "⏳ 等待中";
            case Task.STATUS_RUNNING -> "🔄 执行中";
            case Task.STATUS_FINISHED -> "✅ 已完成";
            case Task.STATUS_FAILED -> "❌ 失败";
            default -> status;
        };
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "无";
        }
        if (text.length() <= maxLen) {
            return text;
        }
        return text.substring(0, maxLen) + "…";
    }
}
