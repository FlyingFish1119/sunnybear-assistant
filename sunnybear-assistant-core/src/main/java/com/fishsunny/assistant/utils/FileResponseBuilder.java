package com.fishsunny.assistant.utils;

/*
 * @Usage 文件下载 / 预览的统一 HTTP 响应构造：流式输出 + Range 断点续传。
 *        之前 /raw 走 Files.readAllBytes 把整个文件读进内存，几百 MB 会直接顶爆堆；
 *        这里改为返回 Resource / ResourceRegion，由 Spring 的流式转换器边读边发，
 *        内存占用与文件大小无关。
 *
 *        支持单段 Range（浏览器下载续传、音视频拖动进度、断点续传都靠它）：
 *        带 Range 头 → 206 + Content-Range；不带 → 200 全量；非法 Range → 416。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/25
 */

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourceRegion;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class FileResponseBuilder {

    private FileResponseBuilder() {
    }

    /**
     * 构造文件响应。
     *
     * @param file        已解析、已通过沙箱校验的真实文件路径
     * @param contentType 内容类型，为空时回退 application/octet-stream
     * @param rangeHeader 原始 Range 请求头，可为空
     * @return 200 全量 / 206 分片 / 404 不存在 / 416 范围非法
     */
    public static ResponseEntity<?> build(Path file, String contentType, String rangeHeader) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }
        Resource resource = new FileSystemResource(file);
        long length = resource.contentLength();
        MediaType mediaType = parseMediaType(contentType);

        if (!StringUtils.hasText(rangeHeader)) {
            return ResponseEntity.ok()
                    .contentType(mediaType)
                    .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                    .cacheControl(CacheControl.noCache())
                    .contentLength(length)
                    .body(resource);
        }

        List<HttpRange> ranges;
        try {
            ranges = HttpRange.parseRanges(rangeHeader);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }
        if (ranges.isEmpty()) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }
        HttpRange range = ranges.get(0);
        long start = range.getRangeStart(length);
        long end = range.getRangeEnd(length);
        if (start >= length) {
            return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE).build();
        }
        long count = end - start + 1;
        // ResourceRegion 由 Spring 的 ResourceRegionHttpMessageConverter 负责写
        // Content-Range / Content-Length，并只读取 [start, start+count) 这一段
        ResourceRegion region = new ResourceRegion(resource, start, count);
        return ResponseEntity.status(HttpStatus.PARTIAL_CONTENT)
                .contentType(mediaType)
                .header(HttpHeaders.ACCEPT_RANGES, "bytes")
                .cacheControl(CacheControl.noCache())
                .body(region);
    }

    private static MediaType parseMediaType(String contentType) {
        if (!StringUtils.hasText(contentType)) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
        try {
            return MediaType.parseMediaType(contentType);
        } catch (Exception e) {
            return MediaType.APPLICATION_OCTET_STREAM;
        }
    }
}
