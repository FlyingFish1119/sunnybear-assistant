package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务查看工具 - 查看任务及其所有步骤的详细信息
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 08:30
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.TaskToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import com.fishsunny.assistant.mvc.service.TaskService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-read.enable:true}")
public class TaskReadTool implements ToolHandler {

    public static final String NAME = "task_read_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskReadTool(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getTaskId())) {
                throw new ToolExecutor.ToolExecuteException("缺少任务 ID");
            }

            // 查询任务
            TaskService.TheTask theTask = taskService.selectTaskById(arguments.getTaskId());
            if (theTask == null) {
                throw new ToolExecutor.ToolExecuteException("任务不存在: " + arguments.getTaskId());
            }

            Task task = theTask.task();
            List<TaskStep> steps = theTask.taskSteps();

            StringBuilder sb = new StringBuilder();
            sb.append("任务详情\n\n");
            sb.append("基本信息：\n");
            sb.append("- ID: ").append(task.getId()).append("\n");
            sb.append("- 名称: **").append(task.getTaskName()).append("**\n");
            sb.append("- 描述: ").append(task.getTaskDesc()).append("\n");
            sb.append("- 状态: ").append(task.getStatus()).append("\n");
            sb.append("- 创建时间: ").append(task.getCreateTime() != null
                    ? task.getCreateTime().format(FORMATTER) : "未知").append("\n");
            if (task.getFinishTime() != null) {
                sb.append("- 完成时间: ").append(task.getFinishTime().format(FORMATTER)).append("\n");
            }
            sb.append("\n步骤列表（共 **").append(steps.size()).append(" 步**）：\n");
            for (int i = 0; i < steps.size(); i++) {
                TaskStep step = steps.get(i);
                sb.append("\n**").append(i + 1).append("**. **").append(step.getStepName()).append("**\n");
                sb.append("   - ID: ").append(step.getId()).append("\n");
                sb.append("   - 描述: ").append(step.getStepDesc()).append("\n");
                sb.append("   - 状态: ").append(step.getStatus()).append("\n");
                sb.append("   - 排序: ").append(step.getSort()).append("\n");
                if (StringUtils.hasText(step.getResult())) {
                    sb.append("   - 执行结果: ").append(step.getResult()).append("\n");
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("查看任务失败：" + e.getMessage());
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
                .setDescription("""
                        查看指定任务的详细信息，包括任务基本信息和所有步骤的详情（含执行状态和结果）。\

                        ## 使用场景\
                        1) 用户询问任务进度，如「我的任务怎么样了」「查看一下XX任务」；\
                        2) 在执行 task_run_tool 之前，先确认任务和步骤内容是否正确；\
                        3) 在删除任务之前，先让用户确认任务内容；\
                        4) 任务执行过程中或执行完毕后，查看各步骤的执行状态和输出结果。\

                        ## 返回内容\
                        返回信息包括：任务 ID、名称、描述、当前状态、创建/完成时间，以及每个步骤的\
                        名称、描述、状态、排序号和执行结果（如果步骤已完成）。\
                        步骤状态包括：waiting（等待执行）、running（执行中）、finished（已完成）、failed（执行失败）。
                        """.replace("\n", " "))
                .setRequired(List.of("taskId"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("taskId", "string", "要查看的任务 ID。可从 task_create_tool 的返回结果中获取，或从之前的对话上下文中查找")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String taskId;
    }
}
