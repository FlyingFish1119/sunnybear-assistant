package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 知识库条目实体 —— 类似 wiki 词条的 K-V 结构
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class KnowledgeRecord {

    private Integer id;
    /** 词条简介（约 50 字，比标题内容更丰富），对话时据此自动挑选注入 */
    private String intro;
    /** 词条内容 */
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
