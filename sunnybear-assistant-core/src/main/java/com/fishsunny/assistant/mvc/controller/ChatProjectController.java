package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 项目管理控制器 —— chat_project 的 CRUD，以及「会话归入 / 移出项目」。
 *        供导航轨的项目面板与会话右键菜单使用。
 *
 *        接口约定与 SessionController / CronJobController 保持一致：
 *        查询走 GET（@RequestMapping），写操作走 POST，返回统一 RestResponse。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/chat/project")
public class ChatProjectController {

    private static final Logger log = LoggerFactory.getLogger(ChatProjectController.class);

    private final ChatProjectService chatProjectService;

    @Autowired
    public ChatProjectController(ChatProjectService chatProjectService) {
        this.chatProjectService = chatProjectService;
    }

    /** 列出所有项目（按最近改动倒序） */
    @RequestMapping("/list")
    public RestResponse list() {
        try {
            return new RestResponse().success(chatProjectService.listAll());
        } catch (Exception e) {
            log.error("获取项目列表失败", e);
            return new RestResponse().error("获取项目列表失败: " + e.getMessage());
        }
    }

    /** 单个项目详情 */
    @RequestMapping("/get")
    public RestResponse get(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("项目 ID 不能为空");
        }
        try {
            ChatProject project = chatProjectService.findById(id);
            if (project == null) {
                return new RestResponse().error("项目不存在");
            }
            return new RestResponse().success(project);
        } catch (Exception e) {
            log.error("获取项目失败", e);
            return new RestResponse().error("获取项目失败: " + e.getMessage());
        }
    }

    /**
     * 某个项目下的全部会话（按最近更新倒序），供项目面板展开查看。
     * 不分页：一个项目手下的会话量级很小，一把拉回来最简单。
     */
    @RequestMapping("/sessions")
    public RestResponse sessions(@RequestParam("projectId") String projectId) {
        if (!StringUtils.hasText(projectId)) {
            return new RestResponse().error("项目 ID 不能为空");
        }
        try {
            return new RestResponse().success(chatProjectService.listSessions(projectId));
        } catch (Exception e) {
            log.error("获取项目会话失败", e);
            return new RestResponse().error("获取项目会话失败: " + e.getMessage());
        }
    }

    /** 新建项目；重名会返回错误信息 */
    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) ProjectForm form) {
        if (form == null) {
            return new RestResponse().error("参数不能为空");
        }
        try {
            return new RestResponse().success(
                    chatProjectService.create(form.getName(), form.getDescription(), form.getColor()));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("创建项目失败", e);
            return new RestResponse().error("创建项目失败: " + e.getMessage());
        }
    }

    /** 修改项目（名字 / 描述 / 颜色） */
    @PostMapping("/update")
    public RestResponse update(@RequestBody(required = false) ProjectForm form) {
        if (form == null || !StringUtils.hasText(form.getId())) {
            return new RestResponse().error("项目 ID 不能为空");
        }
        try {
            return new RestResponse().success(chatProjectService.update(
                    form.getId(), form.getName(), form.getDescription(), form.getColor()));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("修改项目失败", e);
            return new RestResponse().error("修改项目失败: " + e.getMessage());
        }
    }

    /** 删除项目；手下会话会被解绑（会话与消息保留） */
    @PostMapping("/delete")
    public RestResponse delete(@RequestParam("id") String id) {
        if (!StringUtils.hasText(id)) {
            return new RestResponse().error("项目 ID 不能为空");
        }
        try {
            chatProjectService.delete(id);
            return new RestResponse().success(null);
        } catch (Exception e) {
            log.error("删除项目失败", e);
            return new RestResponse().error("删除项目失败: " + e.getMessage());
        }
    }

    /** 把会话归入项目 */
    @PostMapping("/attach")
    public RestResponse attach(@RequestParam("sessionId") String sessionId,
                               @RequestParam("projectId") String projectId) {
        try {
            return new RestResponse().success(chatProjectService.attachSession(sessionId, projectId));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("归入项目失败", e);
            return new RestResponse().error("归入项目失败: " + e.getMessage());
        }
    }

    /** 把会话移出项目 */
    @PostMapping("/detach")
    public RestResponse detach(@RequestParam("sessionId") String sessionId) {
        try {
            return new RestResponse().success(chatProjectService.detachSession(sessionId));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("移出项目失败", e);
            return new RestResponse().error("移出项目失败: " + e.getMessage());
        }
    }

    /** 项目表单（create / update 共用；update 时 id 必填） */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ProjectForm {
        private String id;
        private String name;
        private String description;
        private String color;
    }
}
