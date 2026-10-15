package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 项目文件控制器 —— {basePath}/project/{projectId} 下的文本文件增删改查。
 *        供导航轨项目面板的「项目文件」区使用（列出 / 读取 / 新建 / 保存 / 删除）。
 *
 *        这里不做任何远端转发：文件是磁盘 IO，不走数据库，
 *        动它的就是本地进程里的 ProjectFileManager，与 remote-storage 那套 Repository RPC 无关。
 *
 *        接口约定与 ChatProjectController 保持一致：查询走 GET（@RequestMapping），写操作走 POST。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import com.fishsunny.assistant.utils.ProjectFileManager;
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
@RequestMapping("/chat/project/file")
public class ChatProjectFileController {

    private static final Logger log = LoggerFactory.getLogger(ChatProjectFileController.class);

    private final ProjectFileManager projectFileManager;

    private final ChatProjectService chatProjectService;

    @Autowired
    public ChatProjectFileController(ProjectFileManager projectFileManager, ChatProjectService chatProjectService) {
        this.projectFileManager = projectFileManager;
        this.chatProjectService = chatProjectService;
    }

    /**
     * 列出项目目录下某一层的条目（不递归）。
     *
     * @param dir 相对项目根目录的子目录，不传视为根
     */
    @RequestMapping("/list")
    public RestResponse list(@RequestParam("projectId") String projectId,
                             @RequestParam(value = "dir", required = false) String dir) {
        try {
            return new RestResponse().success(
                    projectFileManager.listProjectFiles(projectId, dir == null ? "" : dir));
        } catch (Exception e) {
            log.error("获取项目文件列表失败", e);
            return new RestResponse().error("获取项目文件列表失败: " + e.getMessage());
        }
    }

    /** 读取文本文件内容（UTF-8；超过 2MB 会被拒绝，错误信息直接给前端提示） */
    @RequestMapping("/read")
    public RestResponse read(@RequestParam("projectId") String projectId,
                             @RequestParam("path") String path) {
        if (!StringUtils.hasText(path)) {
            return new RestResponse().error("文件路径不能为空");
        }
        try {
            String content = projectFileManager.readProjectText(projectId, path);
            if (content == null) {
                return new RestResponse().error("文件不存在: " + path);
            }
            return new RestResponse().success(content);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("读取项目文件失败", e);
            return new RestResponse().error("读取项目文件失败: " + e.getMessage());
        }
    }

    /** 新建文本文件：同名直接报错，不做加序号绕行（避免项目里悄悄长出「同名副本」） */
    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) FileForm form) {
        if (form == null || !StringUtils.hasText(form.getProjectId()) || !StringUtils.hasText(form.getPath())) {
            return new RestResponse().error("项目 ID 与文件路径不能为空");
        }
        try {
            projectFileManager.createProjectText(form.getProjectId(), form.getPath(), form.getContent());
            return new RestResponse().success(null);
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("新建项目文件失败", e);
            return new RestResponse().error("新建项目文件失败: " + e.getMessage());
        }
    }

    /** 保存文本文件（整文件覆盖），父目录不存在时自动创建 */
    @PostMapping("/write")
    public RestResponse write(@RequestBody(required = false) FileForm form) {
        if (form == null || !StringUtils.hasText(form.getProjectId()) || !StringUtils.hasText(form.getPath())) {
            return new RestResponse().error("项目 ID 与文件路径不能为空");
        }
        try {
            projectFileManager.writeProjectText(form.getProjectId(), form.getPath(), form.getContent());
            return new RestResponse().success(null);
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("保存项目文件失败", e);
            return new RestResponse().error("保存项目文件失败: " + e.getMessage());
        }
    }

    /**
     * 删除项目文件。
     * <p>只允许删文件：目录得先清空再删（前端不给删目录的入口），免得一按把整个项目资料带走。
     */
    @PostMapping("/delete")
    public RestResponse delete(@RequestBody(required = false) FileForm form) {
        if (form == null || !StringUtils.hasText(form.getProjectId()) || !StringUtils.hasText(form.getPath())) {
            return new RestResponse().error("项目 ID 与文件路径不能为空");
        }
        try {
            if (!projectFileManager.isProjectFile(form.getProjectId(), form.getPath())) {
                return new RestResponse().error("只能删除文件，不能删除目录: " + form.getPath());
            }
            projectFileManager.deleteProjectFile(form.getProjectId(), form.getPath());
            // 删掉的文件不该再留在自动追加清单里；摘除失败不拦删除主流程，记条日志就行
            try {
                chatProjectService.updateAutoAppendFile(form.getProjectId(), form.getPath(), false);
            } catch (Exception e) {
                log.warn("从自动追加清单里摘除文件失败 projectId={}, file={}: {}",
                        form.getProjectId(), form.getPath(), e.getMessage());
            }
            return new RestResponse().success(null);
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("删除项目文件失败", e);
            return new RestResponse().error("删除项目文件失败: " + e.getMessage());
        }
    }

    /**
     * 切换某个文件是否进「自动追加」清单（清单里的文件，项目会话的第一条消息会带上）。
     * <p>传的是目标状态而不是「翻转」：连点、重试都不会把状态拧反。
     */
    @PostMapping("/auto-append")
    public RestResponse autoAppend(@RequestBody(required = false) AutoAppendForm form) {
        if (form == null || !StringUtils.hasText(form.getProjectId()) || !StringUtils.hasText(form.getPath())) {
            return new RestResponse().error("项目 ID 与文件名不能为空");
        }
        try {
            return new RestResponse().success(
                    chatProjectService.updateAutoAppendFile(form.getProjectId(), form.getPath(), form.isAuto()));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("切换项目文件自动追加失败", e);
            return new RestResponse().error("切换自动追加失败: " + e.getMessage());
        }
    }

    /** 文件表单：create / write / delete 共用 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class FileForm {
        private String projectId;
        private String path;
        private String content;
    }

    /** 自动追加表单：path 是项目根下的文件名，auto 是切换后的目标状态 */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AutoAppendForm {
        private String projectId;
        private String path;
        private boolean auto;
    }
}
