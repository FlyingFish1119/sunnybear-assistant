package com.fishsunny.assistant.mvc.service.implement;

/*
 * @Usage 任务提示词服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;
import com.fishsunny.assistant.mvc.dao.TaskPromptRepository;
import com.fishsunny.assistant.mvc.service.TaskPromptService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TaskPromptServiceImplement implements TaskPromptService {

    private static final Logger log = LoggerFactory.getLogger(TaskPromptServiceImplement.class);

    /** 兜底类型，不可删除 */
    private static final String FALLBACK_TYPE = "general";

    private final TaskPromptRepository taskPromptRepository;

    @Autowired
    public TaskPromptServiceImplement(TaskPromptRepository taskPromptRepository) {
        this.taskPromptRepository = taskPromptRepository;
    }

    @Override
    public TaskPrompt lookup(String type) {
        TaskPrompt prompt = taskPromptRepository.selectByType(type);
        if (prompt != null) {
            return prompt;
        }
        log.warn("未找到 type={} 的提示词，回退到 {}", type, FALLBACK_TYPE);
        TaskPrompt fallback = taskPromptRepository.selectByType(FALLBACK_TYPE);
        if (fallback == null) {
            log.error("task_prompt 表无数据，使用硬编码兜底");
            return new TaskPrompt(FALLBACK_TYPE,
                    "完成步骤「${stepName}」：${stepDesc}。完成后输出步骤总结。",
                    "硬编码兜底");
        }
        return fallback;
    }

    @Override
    public List<TaskPrompt> listAll() {
        return taskPromptRepository.selectAll();
    }

    @Override
    public TaskPrompt save(TaskPrompt prompt) {
        TaskPrompt existing = taskPromptRepository.selectByType(prompt.getType());
        if (existing != null) {
            taskPromptRepository.update(prompt);
        } else {
            taskPromptRepository.insert(prompt);
        }
        return taskPromptRepository.selectByType(prompt.getType());
    }

    @Override
    public TaskPrompt delete(String type) {
        if (FALLBACK_TYPE.equals(type)) {
            log.warn("不允许删除 {} 类型的提示词", FALLBACK_TYPE);
            return null;
        }
        return taskPromptRepository.deleteByType(type);
    }
}
