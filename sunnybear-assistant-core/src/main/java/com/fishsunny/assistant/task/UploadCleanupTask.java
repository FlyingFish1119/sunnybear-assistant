package com.fishsunny.assistant.task;

/*
 * @Usage 定时清理分片上传的过期暂存目录（.upload-tmp）。
 *        上传完成或半途放弃的临时分片不会立刻删，留一段时间供断点续传；
 *        超过保留时长仍无活动的目录才会被清掉，避免磁盘被残片堆积。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/25
 */

import com.fishsunny.assistant.utils.ChunkedUploadManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class UploadCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(UploadCleanupTask.class);

    /** 暂存目录保留时长（小时），超时未活动的上传会被清理 */
    @Value("${assistant.upload.tmp-expire-hours:24}")
    private long expireHours;

    private final ChunkedUploadManager chunkedUploadManager;

    public UploadCleanupTask(ChunkedUploadManager chunkedUploadManager) {
        this.chunkedUploadManager = chunkedUploadManager;
    }

    /** 每小时扫一次 */
    @Scheduled(fixedDelayString = "${assistant.upload.cleanup-interval-ms:3600000}",
            initialDelayString = "${assistant.upload.cleanup-initial-delay-ms:600000}")
    public void cleanup() {
        try {
            chunkedUploadManager.cleanupExpired(Duration.ofHours(Math.max(1, expireHours)));
        } catch (Exception e) {
            // 单次清理失败不影响下次执行
            log.warn("分片上传暂存清理失败: {}", e.getMessage(), e);
        }
    }
}
