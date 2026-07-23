package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 知识库条目实体 —— 类似 wiki 词条的 K-V 结构
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class KnowledgeRecord {

    private Integer id;
    /** 词条标题，也是 embedding 编码的目标 */
    private String title;
    /** 词条内容 */
    private String content;
    /** title 的 embedding 向量（JSON 序列化为 TEXT 存储） */
    private List<Float> embedding;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
