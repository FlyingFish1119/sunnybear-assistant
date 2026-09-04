package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 定时任务实体，对应 cron_job 表
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class CronJob {

    private Integer id;

    /** 任务标题 */
    private String title;

    /** 任务描述 */
    private String description;

    /** cron 表达式 */
    private String cron;

    /** 触发时发送的消息内容 */
    private String message;

    /** 是否启用高级模型 */
    private Boolean enablePro = false;

    /** 无审查模式：开启后该定时任务触发的会话自动跳过工具确认与 AI 危险审查 */
    private Boolean unreviewed = false;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
