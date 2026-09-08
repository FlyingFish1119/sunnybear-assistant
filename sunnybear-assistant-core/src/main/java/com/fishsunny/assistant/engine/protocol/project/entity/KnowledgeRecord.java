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
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class KnowledgeRecord {

    private Integer id;
    /** 词条简介（约 50 字，比标题内容更丰富，少于完整内容），也是 embedding 编码的目标 */
    private String intro;
    /** 词条内容 */
    private String content;
    /** title 的 embedding 向量（JSON 序列化为 TEXT 存储） */
    private List<Float> embedding;
    public KnowledgeRecord setEmbedding(List<Float> embedding) {
        this.embedding = embedding == null ? new ArrayList<>() : embedding;
        return this;
    }

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
