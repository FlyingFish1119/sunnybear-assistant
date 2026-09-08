package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 00:00
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class Task {

    public static final String STATUS_WAITING = "waiting";
    public static final String STATUS_RUNNING = "running";
    public static final String STATUS_FINISHED = "finished";
    public static final String STATUS_FAILED = "failed";

    private String id;

    private String taskName;

    private String taskDesc;

    private String status;
    public Task setStatus(String status) {
        if (!StringUtils.hasText(status)) {
            throw new IllegalArgumentException("Status cannot be empty");
        }
        switch (status) {
            case STATUS_WAITING:
            case STATUS_RUNNING:
            case STATUS_FINISHED:
            case STATUS_FAILED:
                break;
            default:
                throw new IllegalArgumentException("Invalid status: " + status);
        }
        this.status = status;
        return this;
    }

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    public Task() {
    }

    public Task(String taskName, String taskDesc, String status) {
        this.taskName = taskName;
        this.taskDesc = taskDesc;
        setStatus(status);
    }
}
