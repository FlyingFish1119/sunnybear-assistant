package com.fishsunny.assistant.plug.character.entity;

/*
 * @Usage 角色词条实体
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class CharacterGlossary {

    /** 自增主键 */
    private Long id;

    /** 所属角色 ID */
    private String characterId;

    /** 关键词 */
    private String keyword;

    /** 词条描述（简短说明，注入系统提示词） */
    private String desc;

    /** 词条内容（完整内容，AI 工具查询返回） */
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public CharacterGlossary() {
    }
}
