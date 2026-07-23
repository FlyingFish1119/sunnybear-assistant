package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 任务步骤数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5
 */

import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskStepRepository {

    TaskStep insert(TaskStep step);

    /**
     * 仅更新步骤状态，若状态为终态则同时记录完成时间
     */
    TaskStep updateStatus(String id, String status, LocalDateTime finishTime);

    /**
     * 完成步骤：将状态置为 finished，记录执行结果和完成时间
     */
    TaskStep finishStep(String id, String result, LocalDateTime finishTime);

    /**
     * 更新步骤的基本信息（名称、描述、排序）
     */
    TaskStep updateStep(String id, String stepName, String stepDesc, Integer sort);

    /**
     * 仅更新步骤的排序值，用于批量重排
     */
    TaskStep updateSort(String id, int sort);

    TaskStep deleteById(String id);

    List<TaskStep> deleteByTaskId(String taskId);

    TaskStep selectById(String id);

    List<TaskStep> selectByTaskId(String taskId);

    List<TaskStep> selectAll();
}
