package com.fishsunny.assistant.plug.world.entity;

/*
 * @Usage 世界观知识条目实体（标题 + 内容 + 知晓该知识的角色 id 列表）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Accessors(chain = true)
public class WorldKnowledge {

    /** 知识 ID（主键） */
    private String id;

    /** 所属世界观 ID */
    private String worldId;

    /** 知识标题 */
    private String title;

    /** 知识内容 */
    private String content;

    /** 知晓该知识的角色 id 列表（瞬态，来自关联表，不落库） */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<String> characterIds;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public WorldKnowledge() {
    }
}
