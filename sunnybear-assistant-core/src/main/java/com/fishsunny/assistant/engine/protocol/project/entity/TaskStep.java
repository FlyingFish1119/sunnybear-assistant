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

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class TaskStep {

    private String id;

    private String taskId;

    private String stepName;

    private String stepDesc;

    private String result;

    private String status;

    private Integer sort;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishTime;

    public TaskStep() {
    }

    public TaskStep(String taskId, String stepName, String stepDesc, String result, String status, Integer sort) {
        this.taskId = taskId;
        this.stepName = stepName;
        this.stepDesc = stepDesc;
        this.result = result;
        this.status = status;
        this.sort = sort;
    }
}
