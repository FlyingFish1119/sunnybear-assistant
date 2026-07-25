package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 任务提示词数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;

import java.util.List;

public interface TaskPromptRepository {

    TaskPrompt selectByType(String type);

    List<TaskPrompt> selectAll();

    void insert(TaskPrompt prompt);

    void update(TaskPrompt prompt);

    TaskPrompt deleteByType(String type);
}
