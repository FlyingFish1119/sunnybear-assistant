package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 任务提示词服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;

import java.util.List;

public interface TaskPromptService {

    /**
     * 按 type 查找提示词，找不到时返回 "general" 类型作为回退
     */
    TaskPrompt lookup(String type);

    List<TaskPrompt> listAll();

    /**
     * 新增或更新提示词。如果 type 已存在则更新，否则插入。
     */
    TaskPrompt save(TaskPrompt prompt);

    /**
     * 删除提示词。type 为 "general" 时不允许删除。
     * 返回被删除的条目，type 不存在时返回 null。
     */
    TaskPrompt delete(String type);
}
