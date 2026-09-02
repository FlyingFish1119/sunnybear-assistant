package com.fishsunny.assistant.engine.tool.instance.task;

/*
 * @Usage 任务步骤更新工具 - 在用户确认计划后，根据反馈意见调整步骤
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 07:07
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.TaskToolKit;
import com.fishsunny.assistant.mvc.service.TaskService;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@ToolKitComponent(TaskToolKit.class)
@ConditionalOnExpression("${engine.tool.task.enable:true} && ${engine.tool.task.task-step-update.enable:true}")
public class TaskStepUpdateTool implements ToolHandler {

    public static final String NAME = "task_step_update_tool";

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    @Autowired
    public TaskStepUpdateTool(TaskService taskService, ObjectMapper objectMapper) {
        this.taskService = taskService;
        this.objectMapper = objectMapper;
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        try {
            Arguments arguments = objectMapper.readValue(argumentsJson, Arguments.class);

            // --- 参数校验 ---
            if (!StringUtils.hasText(arguments.getTaskId())) {
                throw new ToolExecutor.ToolExecuteException("缺少任务 ID（taskId），无法定位要修改的任务");
            }
            if (!StringUtils.hasText(arguments.getStepId())) {
                throw new ToolExecutor.ToolExecuteException("缺少步骤 ID（stepId），无法定位要修改的步骤");
            }

            // 校验父任务存在
            TaskService.TheTask theTask = taskService.selectTaskById(arguments.getTaskId());
            if (theTask == null) {
                throw new ToolExecutor.ToolExecuteException("任务不存在: taskId=" + arguments.getTaskId());
            }

            // 校验步骤属于该任务
            boolean stepBelongsToTask = theTask.taskSteps().stream()
                    .anyMatch(s -> s.getId().equals(arguments.getStepId()));
            if (!stepBelongsToTask) {
                throw new ToolExecutor.ToolExecuteException(
                        "步骤[" + arguments.getStepId() + "]不属于任务[" + arguments.getTaskId() + "]");
            }

            // 校验 sort 合法性
            if (arguments.getSort() != null && arguments.getSort() < 0) {
                throw new ToolExecutor.ToolExecuteException("排序值 sort 不能为负数");
            }

            // --- 调用 Service 更新 ---
            TaskStep updated = taskService.updateTaskStep(
                    arguments.getStepId(),
                    arguments.getName(),
                    arguments.getDesc(),
                    arguments.getSort()
            );

            // --- 构建返回信息 ---
            StringBuilder sb = new StringBuilder();
            sb.append("步骤更新成功\n\n");
            sb.append("- 步骤 ID: ").append(updated.getId()).append("\n");
            sb.append("- 名称: **").append(updated.getStepName()).append("**\n");
            sb.append("- 描述: ").append(updated.getStepDesc()).append("\n");
            sb.append("- 排序: ").append(updated.getSort()).append("\n");

            // 如果排序发生了变化，列出重排后的完整步骤顺序
            if (arguments.getSort() != null) {
                TaskService.TheTask refreshed = taskService.selectTaskById(arguments.getTaskId());
                sb.append("\n排序已重算，当前所有步骤顺序：\n");
                for (int i = 0; i < refreshed.taskSteps().size(); i++) {
                    TaskStep s = refreshed.taskSteps().get(i);
                    sb.append(i + 1).append(". **").append(s.getStepName()).append("**");
                    if (s.getId().equals(updated.getId())) {
                        sb.append(" ← 本次修改");
                    }
                    sb.append("\n");
                }
            }

            return new ToolExecutor.ToolExecuteResponse(name(), sb.toString());
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("更新任务步骤失败：" + e.getMessage());
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
                        更新任务步骤的名称、描述或排序位置。适用于执行前根据用户反馈调整步骤。
                        """.replace("\n", " "))
                .setRequired(List.of("taskId", "stepId"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("taskId", "string", "任务 ID，必填。用于校验步骤归属并在修改排序后重算所有步骤顺序"),
                        new ToolRegister.Parameters("stepId", "string", "步骤 ID，必填。要修改的目标步骤唯一标识"),
                        new ToolRegister.Parameters("name", "string", "新的步骤名称，选填。用户要求修改标题时传入，否则不传"),
                        new ToolRegister.Parameters("desc", "string", "新的步骤描述，选填。用户要求补充或修改描述时传入，否则不传"),
                        new ToolRegister.Parameters("sort", "integer", "新的排序位置（从 0 开始），选填。用户要求调整执行顺序时传入。修改后会自动重排同任务下所有步骤为连续序号")
                ));
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String taskId;
        private String stepId;
        private String name;
        private String desc;
        private Integer sort;
    }
}
