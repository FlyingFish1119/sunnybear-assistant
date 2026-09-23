package com.fishsunny.assistant.engine.protocol.project.entity;

/*
 * @Usage 核心记忆实体，对应 chat_memory 表
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
public class MemoryRecord {

    /** 默认分组名：不带分组的新增（含老接口、工具调用）都落这里 */
    public static final String GROUP_UNCLASSIFIED = "未分类";

    private Integer id;
    private String content;
    /** 分组名（如「我是谁」「我的习惯」），存量数据默认「未分类」 */
    private String groupName;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
