package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 定时任务管理控制器，提供 cron_job 的 CRUD 接口（供 settings 页面使用）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/30
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.mvc.service.CronJobService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/cron-job")
public class CronJobController {

    private static final Logger log = LoggerFactory.getLogger(CronJobController.class);

    private final CronJobService cronJobService;

    @Autowired
    public CronJobController(CronJobService cronJobService) {
        this.cronJobService = cronJobService;
    }

    /** 列出所有定时任务 */
    @RequestMapping("/list")
    public RestResponse list() {
        try {
            List<CronJob> list = cronJobService.listAll();
            return new RestResponse().success(list);
        } catch (Exception e) {
            log.error("获取定时任务列表失败", e);
            return new RestResponse().error("获取定时任务列表失败: " + e.getMessage());
        }
    }

    /** 根据 ID 获取单条定时任务 */
    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") Integer id) {
        if (id == null) {
            return new RestResponse().error("id 不能为空");
        }
        try {
            CronJob job = cronJobService.findById(id);
            if (job == null) {
                return new RestResponse().error("定时任务不存在");
            }
            return new RestResponse().success(job);
        } catch (Exception e) {
            log.error("获取定时任务失败", e);
            return new RestResponse().error("获取定时任务失败: " + e.getMessage());
        }
    }

    /** 新增或更新定时任务。请求体: { title, description, cron, message, enablePro }，更新时加 id */
    @PostMapping("/save")
    public RestResponse save(@RequestBody(required = false) Map<String, Object> body) {
        if (body == null) {
            return new RestResponse().error("请求体不能为空");
        }
        try {
            String title = (String) body.get("title");
            String description = (String) body.getOrDefault("description", "");
            String cron = (String) body.get("cron");
            String message = (String) body.get("message");
            boolean enablePro = Boolean.TRUE.equals(body.get("enablePro"));

            if (!StringUtils.hasText(title)) {
                return new RestResponse().error("title 不能为空");
            }
            if (!StringUtils.hasText(cron)) {
                return new RestResponse().error("cron 不能为空");
            }
            if (!StringUtils.hasText(message)) {
                return new RestResponse().error("message 不能为空");
            }

            Object idObj = body.get("id");
            CronJob saved;
            if (idObj != null) {
                Integer id = idObj instanceof Number ? ((Number) idObj).intValue() : Integer.parseInt(idObj.toString());
                saved = cronJobService.update(id, title.trim(), description != null ? description.trim() : "",
                        cron.trim(), message, enablePro);
            } else {
                saved = cronJobService.create(title.trim(), description != null ? description.trim() : "",
                        cron.trim(), message, enablePro);
            }
            return new RestResponse().success(saved);
        } catch (Exception e) {
            log.error("保存定时任务失败", e);
            return new RestResponse().error("保存定时任务失败: " + e.getMessage());
        }
    }

    /** 删除定时任务 */
    @RequestMapping("/delete")
    public RestResponse delete(@RequestParam("id") Integer id) {
        if (id == null) {
            return new RestResponse().error("id 不能为空");
        }
        try {
            CronJob deleted = cronJobService.delete(id);
            if (deleted == null) {
                return new RestResponse().error("定时任务不存在");
            }
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除定时任务失败", e);
            return new RestResponse().error("删除定时任务失败: " + e.getMessage());
        }
    }
}
