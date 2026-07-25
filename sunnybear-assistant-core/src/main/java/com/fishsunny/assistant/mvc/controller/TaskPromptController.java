package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 任务提示词管理控制器，提供提示词的 CRUD 接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;
import com.fishsunny.assistant.mvc.service.TaskPromptService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/task-prompt")
public class TaskPromptController {

    private static final Logger log = LoggerFactory.getLogger(TaskPromptController.class);

    private final TaskPromptService taskPromptService;

    @Autowired
    public TaskPromptController(TaskPromptService taskPromptService) {
        this.taskPromptService = taskPromptService;
    }

    /** 列出所有提示词 */
    @RequestMapping("/list")
    public RestResponse list() {
        try {
            List<TaskPrompt> list = taskPromptService.listAll();
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取提示词列表失败", e);
            return new RestResponse().error("获取提示词列表失败: " + e.getMessage());
        }
    }

    /** 根据 type 获取单条提示词 */
    @RequestMapping("/get")
    public RestResponse get(@RequestParam("type") String type) {
        if (!StringUtils.hasText(type)) {
            return new RestResponse().error("type 不能为空");
        }
        try {
            TaskPrompt prompt = taskPromptService.lookup(type);
            if (prompt == null) {
                return new RestResponse().error("提示词不存在");
            }
            return new RestResponse().success(prompt);
        } catch (Exception e) {
            log.error("获取提示词失败", e);
            return new RestResponse().error("获取提示词失败: " + e.getMessage());
        }
    }

    /** 新增或更新提示词。请求体: { type, prompt, description } */
    @PostMapping("/save")
    public RestResponse save(@RequestBody(required = false) Map<String, String> body) {
        if (body == null) {
            return new RestResponse().error("请求体不能为空");
        }
        try {
            String type = body.get("type");
            String prompt = body.get("prompt");
            String description = body.getOrDefault("description", "");

            if (!StringUtils.hasText(type)) {
                return new RestResponse().error("type 不能为空");
            }
            if (!StringUtils.hasText(prompt)) {
                return new RestResponse().error("prompt 不能为空");
            }

            TaskPrompt saved = taskPromptService.save(
                    new TaskPrompt(type.trim(), prompt, description));
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("保存提示词失败", e);
            return new RestResponse().error("保存提示词失败: " + e.getMessage());
        }
    }

    /** 删除提示词 */
    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("type") String type) {
        if (!StringUtils.hasText(type)) {
            return new RestResponse().error("type 不能为空");
        }
        try {
            TaskPrompt deleted = taskPromptService.delete(type.trim());
            if (deleted == null) {
                return new RestResponse().error("提示词不存在或不允许删除（general 类型不可删除）");
            }
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除提示词失败", e);
            return new RestResponse().error("删除提示词失败: " + e.getMessage());
        }
    }
}
