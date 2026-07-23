package com.fishsunny.assistant.mvc.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 本地文件代理控制器
 * <p>
 * 将本地文件通过 HTTP 提供给浏览器，用于展示图片、音视频等本地文件。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/12
 */
@RestController
@RequestMapping("/file")
public class FileProxyController {

    private static final Logger log = LoggerFactory.getLogger(FileProxyController.class);

    /** 代理文件缓存最大存活时间（秒） */
    private static final int PROXY_CACHE_MAX_AGE = 3600;

    @GetMapping("/proxy")
    public ResponseEntity<byte[]> proxyLocalFile(@RequestParam("path") String path) {
        try {
            Path filePath = Paths.get(path).normalize();
            // 防路径遍历
            if (filePath.toString().contains("..")) {
                return ResponseEntity.badRequest().build();
            }
            if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
                return ResponseEntity.notFound().build();
            }
            // 允许常见媒体及文档类型
            String contentType = Files.probeContentType(filePath);
            if (contentType == null || !isAllowedContentType(contentType)) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
            }
            byte[] data = Files.readAllBytes(filePath);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(contentType));
            headers.setCacheControl("max-age=" + PROXY_CACHE_MAX_AGE);
            return new ResponseEntity<>(data, headers, HttpStatus.OK);
        } catch (Exception e) {
            log.warn("代理文件失败: {}", path, e);
            return ResponseEntity.badRequest().build();
        }
    }

    /** 允许代理的 MIME 类型 */
    private boolean isAllowedContentType(String contentType) {
        if (contentType.startsWith("image/")) return true;
        if (contentType.startsWith("audio/")) return true;
        if (contentType.startsWith("video/")) return true;
        if (contentType.startsWith("text/")) return true;
        // 常见文档 / 压缩包
        return switch (contentType) {
            case "application/pdf",
                 "application/json",
                 "application/xml",
                 "application/zip",
                 "application/x-tar",
                 "application/gzip",
                 "application/x-7z-compressed",
                 "application/vnd.rar",
                 "application/msword",
                 "application/vnd.ms-excel",
                 "application/vnd.ms-powerpoint" -> true;
            default -> contentType.startsWith("application/vnd.openxmlformats-officedocument.");
        };
    }
}
