package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 核心记忆实体，对应 chat_memory 表
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class MemoryRecord {

    private Integer id;
    private String content;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
