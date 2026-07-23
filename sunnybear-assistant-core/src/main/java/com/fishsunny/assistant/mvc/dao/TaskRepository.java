package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 任务数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5
 */

import com.fishsunny.assistant.engine.protocol.project.entity.Task;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskRepository {

    Task insert(Task task);

    /**
     * 仅更新任务状态，若状态为终态则同时记录完成时间
     */
    Task updateStatus(String id, String status, LocalDateTime finishTime);

    /**
     * 完成任务：将状态置为 finished 并记录完成时间
     */
    Task finishTask(String id, LocalDateTime finishTime);

    /**
     * 删除任务，同时级联删除该任务下的所有步骤
     */
    Task deleteById(String id);

    Task selectById(String id);

    List<Task> selectAll();

    /**
     * 分页查询任务列表，按创建时间倒序
     */
    List<Task> selectAll(int limit, int offset);

    /**
     * 查询任务总数
     */
    int count();
}
