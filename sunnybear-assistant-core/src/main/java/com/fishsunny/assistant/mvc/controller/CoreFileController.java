package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 核心文件管理接口 —— 供前端「文件资源栏」的核心文件模式直接调用。
 *        能力实体下沉在 CoreFileManager，本类只做参数校验和异常转译，不重复实现文件逻辑。
 *
 *        所有路径参数都是「相对 data/core」的相对路径，统一经
 *        SessionFileManager.resolveUnder 做沙箱解析：
 *        不允许绝对路径、盘符路径、以分隔符开头、以及 .. 回溯。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/22
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.utils.CoreFileManager;
import com.fishsunny.assistant.utils.FileResponseBuilder;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;

@RestController
@RequestMapping("/core/file")
public class CoreFileController {

    private static final Logger log = LoggerFactory.getLogger(CoreFileController.class);

    private final CoreFileManager coreFileManager;

    public CoreFileController(CoreFileManager coreFileManager) {
        this.coreFileManager = coreFileManager;
    }

    /**
     * 列出核心文件目录的某一层（不递归）。
     *
     * @param dir 相对 data/core 的子目录，空串表示根目录
     */
    @GetMapping("/list")
    public RestResponse list(@RequestParam(required = false, defaultValue = "") String dir) {
        try {
            return new RestResponse().success(coreFileManager.listCoreFiles(dir));
        } catch (Exception e) {
            log.error("列出核心文件失败: dir={}", dir, e);
            return new RestResponse().error("列出核心文件失败: " + e.getMessage());
        }
    }

    /** 读取文本内容（UTF-8；超过 2MB 会拒绝，提示下载后查看） */
    @GetMapping("/read")
    public RestResponse read(@RequestParam(required = false) String path) {
        if (!StringUtils.hasText(path)) {
            return new RestResponse().error("文件路径不能为空");
        }
        try {
            String content = coreFileManager.readCoreText(path);
            if (content == null) {
                return new RestResponse().error("文件不存在: " + path);
            }
            return new RestResponse().success(content);
        } catch (Exception e) {
            log.error("读取核心文件失败: path={}", path, e);
            return new RestResponse().error("读取失败: " + e.getMessage());
        }
    }

    /**
     * 流式输出文件，供图片预览 / 加载到发送栏 / 二进制下载 / 断点续传使用。
     * <p>带 Range 头时返回 206 + 指定片段（浏览器下载续传、音视频拖动进度依赖它）。
     */
    @GetMapping("/raw")
    public ResponseEntity<?> raw(@RequestParam(required = false) String path,
                                 @RequestHeader(value = HttpHeaders.RANGE, required = false) String range) {
        if (!StringUtils.hasText(path)) {
            return ResponseEntity.badRequest().build();
        }
        try {
            Path file = coreFileManager.resolveCoreFilePath(path);
            return FileResponseBuilder.build(file, Files.probeContentType(file), range);
        } catch (Exception e) {
            log.warn("读取核心文件失败: path={}", path, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** 写回文本（整文件覆盖） */
    @PostMapping("/write")
    public RestResponse write(@RequestBody(required = false) FileRequest request) {
        String invalid = validate(request);
        if (invalid != null) {
            return new RestResponse().error(invalid);
        }
        try {
            coreFileManager.writeCoreText(request.getPath(), request.getContent());
            return new RestResponse().success(request.getPath());
        } catch (Exception e) {
            log.error("写入核心文件失败: path={}", request.getPath(), e);
            return new RestResponse().error("写入失败: " + e.getMessage());
        }
    }

    /** 新建文件（同名文件已存在时报错，不覆盖） */
    @PostMapping("/create")
    public RestResponse create(@RequestBody(required = false) FileRequest request) {
        String invalid = validate(request);
        if (invalid != null) {
            return new RestResponse().error(invalid);
        }
        try {
            coreFileManager.createCoreText(request.getPath(), request.getContent());
            return new RestResponse().success(request.getPath());
        } catch (Exception e) {
            log.error("新建核心文件失败: path={}", request.getPath(), e);
            return new RestResponse().error("新建失败: " + e.getMessage());
        }
    }

    /** 改名 / 移动（目标已存在时报错） */
    @PostMapping("/rename")
    public RestResponse rename(@RequestBody(required = false) FileRequest request) {
        String invalid = validate(request);
        if (invalid != null) {
            return new RestResponse().error(invalid);
        }
        if (!StringUtils.hasText(request.getNewPath())) {
            return new RestResponse().error("新文件名不能为空");
        }
        try {
            coreFileManager.renameCoreFile(request.getPath(), request.getNewPath());
            return new RestResponse().success(request.getNewPath());
        } catch (Exception e) {
            log.error("重命名核心文件失败: path={}, newPath={}", request.getPath(), request.getNewPath(), e);
            return new RestResponse().error("重命名失败: " + e.getMessage());
        }
    }

    /** 删除文件或空目录（非空目录会被拒绝） */
    @PostMapping("/delete")
    public RestResponse delete(@RequestBody(required = false) FileRequest request) {
        String invalid = validate(request);
        if (invalid != null) {
            return new RestResponse().error(invalid);
        }
        try {
            coreFileManager.deleteCoreFile(request.getPath());
            return new RestResponse().success("删除成功");
        } catch (Exception e) {
            log.error("删除核心文件失败: path={}", request.getPath(), e);
            return new RestResponse().error("删除失败: " + e.getMessage());
        }
    }

    /**
     * 上传文件（multipart/form-data）。同名自动加序号，返回落盘后的相对路径。
     *
     * @param path 目标子目录，空串表示根目录
     */
    @PostMapping("/upload")
    public RestResponse upload(@RequestParam("file") MultipartFile file,
                               @RequestParam(required = false, defaultValue = "") String path) {
        if (file == null || file.isEmpty()) {
            return new RestResponse().error("文件不能为空");
        }
        try {
            String savedPath = coreFileManager.saveUpload(path, file.getOriginalFilename(), file.getBytes());
            return new RestResponse().success(savedPath);
        } catch (Exception e) {
            log.error("上传核心文件失败: dir={}, name={}", path, file.getOriginalFilename(), e);
            return new RestResponse().error("上传失败: " + e.getMessage());
        }
    }

    /**
     * 会话文件转存到核心库（「提升为核心」）：复制一份到核心根目录，原文件保留不动。
     * 同名自动加序号，返回落盘后的相对路径。
     */
    @PostMapping("/promote")
    public RestResponse promote(@RequestBody(required = false) FileRequest request) {
        String invalid = validate(request);
        if (invalid != null) {
            return new RestResponse().error(invalid);
        }
        if (!StringUtils.hasText(request.getSessionId())) {
            return new RestResponse().error("会话 ID 不能为空");
        }
        try {
            String savedPath = coreFileManager.promoteFromSession(request.getSessionId(), request.getPath());
            return new RestResponse().success(savedPath);
        } catch (Exception e) {
            log.error("提升会话文件失败: sessionId={}, path={}", request.getSessionId(), request.getPath(), e);
            return new RestResponse().error("提升失败: " + e.getMessage());
        }
    }

    /** 公共校验：request 与 path 必有 */
    private String validate(FileRequest request) {
        if (request == null) {
            return "请求体不能为空";
        }
        if (!StringUtils.hasText(request.getPath())) {
            return "文件路径不能为空";
        }
        return null;
    }

    /** 请求体：各动作按需取用字段 */
    @Data
    public static class FileRequest {

        /** 文件相对 data/core 的路径（写/新建/改名/删除/转存的源路径） */
        private String path;

        /** 改名 / 移动的目标相对路径 */
        private String newPath;

        /** 写入内容（允许为空，表示空文件） */
        private String content;

        /** 会话 ID（仅 promote 用） */
        private String sessionId;
    }
}
