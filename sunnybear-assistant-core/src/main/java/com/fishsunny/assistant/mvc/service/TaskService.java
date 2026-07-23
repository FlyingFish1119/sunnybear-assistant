package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 任务与步骤服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 07:13
 */

import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;

import java.util.List;

public interface TaskService {

    TheTask createTheTask(Task task, List<TaskStep> taskSteps);

    /**
     * 仅更新任务状态（AI 不应修改任务内容，需修改时应重新创建）
     */
    Task updateTaskStatus(String id, String status);

    /**
     * 完成任务：将任务标记为 finished
     */
    Task finishTask(String id);

    Task deleteTask(String id);

    TheTask selectTaskById(String id);

    TaskStep createTaskStep(TaskStep taskStep);

    /**
     * 仅更新步骤状态（AI 不应修改步骤内容，需修改时应重新创建）
     */
    TaskStep updateTaskStepStatus(String id, String status);

    /**
     * 完成步骤：将步骤标记为 finished 并记录执行结果
     */
    TaskStep finishStep(String id, String result);

    /**
     * 更新任务步骤的基本信息（名称、描述、排序）。
     * 当排序值变更时，会自动重算同任务下所有步骤的排序，保证连续无重复。
     *
     * @param stepId   步骤 ID（必填）
     * @param stepName 新的步骤名称（可选，为 null 则不更新）
     * @param stepDesc 新的步骤描述（可选，为 null 则不更新）
     * @param sort     新的排序位置（可选，为 null 则不更新）
     * @return 更新后的步骤
     */
    TaskStep updateTaskStep(String stepId, String stepName, String stepDesc, Integer sort);

    TaskStep deleteTaskStep(String id);

    /**
     * 分页查询外层主任务（不含步骤），按创建时间倒序
     */
    TaskListResult selectTasks(int limit, int offset);

    record TheTask(Task task, List<TaskStep> taskSteps) {}

    record TaskListResult(List<Task> tasks, int total) {}
}
