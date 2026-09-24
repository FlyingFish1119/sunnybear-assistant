package com.fishsunny.assistant.mvc.controller;

/*
 * @Usage 分片断点上传接口 —— 供前端「文件资源栏」上传大文件使用。
 *        小文件仍走原来的 /session/file/upload、/core/file/upload（multipart 单请求）；
 *        大文件走这里：init → 若干 chunk → complete，中途断开可凭 uploadId 续传。
 *
 *        chunk 接口用裸 body（application/octet-stream）而非 multipart，
 *        因此不受 spring.servlet.multipart.max-file-size（50MB）限制。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/25
 */

import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.utils.ChunkedUploadManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/upload")
public class ChunkedUploadController {

    private static final Logger log = LoggerFactory.getLogger(ChunkedUploadController.class);

    private final ChunkedUploadManager chunkedUploadManager;

    public ChunkedUploadController(ChunkedUploadManager chunkedUploadManager) {
        this.chunkedUploadManager = chunkedUploadManager;
    }

    /** 初始化 / 恢复一次上传，返回已收到的分片序号（断点） */
    @PostMapping("/init")
    public RestResponse init(@RequestBody(required = false) ChunkedUploadManager.InitRequest request) {
        try {
            return new RestResponse().success(chunkedUploadManager.init(request));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("分片上传初始化失败: {}", e.getMessage(), e);
            return new RestResponse().error("初始化失败: " + e.getMessage());
        }
    }

    /** 查询已收到的分片（页面刷新 / 重试时用） */
    @GetMapping("/status")
    public RestResponse status(@RequestParam(required = false) String uploadId) {
        try {
            return new RestResponse().success(chunkedUploadManager.status(uploadId));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("查询分片上传状态失败: uploadId={}", uploadId, e);
            return new RestResponse().error("查询失败: " + e.getMessage());
        }
    }

    /** 上传单个分片（body 为该分片的原始字节，Content-Type: application/octet-stream） */
    @PostMapping("/chunk")
    public RestResponse chunk(@RequestParam(required = false) String uploadId,
                              @RequestParam(defaultValue = "-1") int index,
                              @RequestBody(required = false) byte[] data) {
        try {
            int received = chunkedUploadManager.saveChunk(uploadId, index, data);
            return new RestResponse().success(received);
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("分片上传失败: uploadId={}, index={}", uploadId, index, e);
            return new RestResponse().error("分片上传失败: " + e.getMessage());
        }
    }

    /** 全部分片到齐后合并落盘，返回最终相对路径；幂等，可重复调用 */
    @PostMapping("/complete")
    public RestResponse complete(@RequestParam(required = false) String uploadId) {
        try {
            return new RestResponse().success(chunkedUploadManager.complete(uploadId));
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("分片上传合并失败: uploadId={}", uploadId, e);
            return new RestResponse().error("合并失败: " + e.getMessage());
        }
    }

    /** 放弃上传并清理暂存分片 */
    @PostMapping("/abort")
    public RestResponse abort(@RequestParam(required = false) String uploadId) {
        try {
            chunkedUploadManager.abort(uploadId);
            return new RestResponse().success("已放弃");
        } catch (IllegalArgumentException e) {
            return new RestResponse().error(e.getMessage());
        } catch (Exception e) {
            log.error("放弃分片上传失败: uploadId={}", uploadId, e);
            return new RestResponse().error("放弃失败: " + e.getMessage());
        }
    }
}
