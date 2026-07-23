package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务创建工具 - 创建任务及其步骤
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 07:07
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-create.enable:true}")
public class TaskCreateTool implements ToolHandler {

    public static final String NAME = "task_create_tool";

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskCreateTool(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (!StringUtils.hasText(arguments.getName())) {
                throw new ToolExecutor.ToolExecuteException("缺少任务名称");
            }
            if (!StringUtils.hasText(arguments.getDesc())) {
                throw new ToolExecutor.ToolExecuteException("缺少任务描述");
            }
            if (arguments.getSteps() == null || arguments.getSteps().isEmpty()) {
                throw new ToolExecutor.ToolExecuteException("缺少任务步骤");
            }

            // 构建 Task 实体
            Task task = new Task(arguments.getName(), arguments.getDesc(), Task.STATUS_WAITING);

            // 构建 TaskStep 列表
            List<TaskStep> taskSteps = new ArrayList<>();
            for (int i = 0; i < arguments.getSteps().size(); i++) {
                Arguments.Steps s = arguments.getSteps().get(i);
                if (!StringUtils.hasText(s.getName())) {
                    throw new ToolExecutor.ToolExecuteException("步骤[" + (i + 1) + "]名称不能为空");
                }
                if (!StringUtils.hasText(s.getDesc())) {
                    throw new ToolExecutor.ToolExecuteException("步骤[" + (i + 1) + "]描述不能为空");
                }
                taskSteps.add(new TaskStep(null, s.getName(), s.getDesc(), "", Task.STATUS_WAITING, i));
            }

            // 调用 Service 创建
            TaskService.TheTask result = taskService.createTheTask(task, taskSteps);

            // 构建返回信息
            StringBuilder sb = new StringBuilder();
            sb.append("任务创建成功\n\n");
            sb.append("任务信息：\n");
            sb.append("- ID: ").append(result.task().getId()).append("\n");
            sb.append("- 名称: **").append(result.task().getTaskName()).append("**\n");
            sb.append("- 描述: ").append(result.task().getTaskDesc()).append("\n");
            sb.append("- 状态: ").append(result.task().getStatus()).append("\n");
            sb.append("- 创建时间: ").append(result.task().getCreateTime() != null
                    ? result.task().getCreateTime().format(FORMATTER) : "未知").append("\n");
            sb.append("\n步骤列表（共 **").append(result.taskSteps().size()).append(" 步**）：\n");
            for (int i = 0; i < result.taskSteps().size(); i++) {
                TaskStep step = result.taskSteps().get(i);
                sb.append("\n**").append(i + 1).append("**. **").append(step.getStepName()).append("**\n");
                sb.append("   - ID: ").append(step.getId()).append("\n");
                sb.append("   - 描述: ").append(step.getStepDesc()).append("\n");
                sb.append("   - 状态: ").append(step.getStatus()).append("\n");
                sb.append("   - 排序: ").append(step.getSort()).append("\n");
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("创建任务失败：" + e.getMessage());
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
                        创建一个新任务及其执行步骤。当需要为用户制定多步骤执行计划时使用，\
                        例如「帮我做一个XX项目」「请完成XX任务」等需要拆解为多个子步骤的复杂需求。\
                        创建后状态为「等待中」，需要使用 task_run_tool 来启动执行。
                        """.replace("\n", " "))
                .setRequired(List.of("name", "desc", "steps"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("name", "string", "任务名称，简洁概括任务目标。例如：「实现用户登录模块」「重构数据库访问层」「部署项目到服务器」"),
                        new ToolRegister.Parameters("desc", "string", "任务详细描述，说明最终产出、验收标准和预期效果。越具体越好，这会影响 AI 对整体目标的理解"),
                        new ToolRegister.Parameters("steps", "array", "任务步骤列表。每个元素为一个对象，包含：name（步骤简短标题，如「设计数据库表结构」）和 desc（步骤详细描述，即该步骤 AI 需要完成的具体工作，越具体执行效果越好）。步骤按数组顺序依次执行")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String name;
        private String desc;
        private List<Steps> steps;

        @Data
        @Accessors(chain = true)
        public static class Steps {
            private String name;
            private String desc;
        }
    }
}
