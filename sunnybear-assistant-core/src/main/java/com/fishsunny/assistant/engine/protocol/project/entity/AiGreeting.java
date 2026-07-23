package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage AI 问候语实体
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class AiGreeting {

    private String id;

    private String text;

    /**
     * 生成问候语时的时间上下文（如 "morning", "afternoon", "evening", "night"）
     */
    private String greetingTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public AiGreeting() {
    }
}
