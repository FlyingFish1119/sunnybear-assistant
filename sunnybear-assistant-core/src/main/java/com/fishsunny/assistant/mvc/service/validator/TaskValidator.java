package com.fishsunny.assistant.mvc.service.validator;

/*
 * @Usage 任务与步骤统一校验
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 07:21
 */

import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.exception.UserException;
import org.springframework.util.StringUtils;

public class TaskValidator {

    public static void createTask(Task task) {
        if (task == null) {
            throw new UserException("任务不能为空");
        }
        if (!StringUtils.hasText(task.getTaskName())) {
            throw new UserException("任务名称不能为空");
        }
        if (!StringUtils.hasText(task.getTaskDesc())) {
            throw new UserException("任务描述不能为空");
        }
    }

    public static void createTaskStep(TaskStep step) {
        if (step == null) {
            throw new UserException("任务步骤不能为空");
        }
        if (!StringUtils.hasText(step.getTaskId())) {
            throw new UserException("任务 ID 不能为空");
        }
        if (!StringUtils.hasText(step.getStepName())) {
            throw new UserException("任务步骤名称不能为空");
        }
        if (!StringUtils.hasText(step.getStepDesc())) {
            throw new UserException("任务步骤描述不能为空");
        }
        if (step.getSort() == null || step.getSort() < 0) {
            throw new UserException("任务步骤排序不合法");
        }
    }

    /**
     * 校验状态值是否合法
     */
    public static void validateStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new UserException("任务状态不能为空");
        }
        switch (status) {
            case Task.STATUS_WAITING:
            case Task.STATUS_RUNNING:
            case Task.STATUS_FINISHED:
            case Task.STATUS_FAILED:
                break;
            default:
                throw new UserException("任务状态[" + status + "]无效");
        }
    }

    /**
     * 判断是否为终态（已完成或已失败）
     */
    public static boolean isTerminalStatus(String status) {
        return Task.STATUS_FINISHED.equals(status) || Task.STATUS_FAILED.equals(status);
    }
}
