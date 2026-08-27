package com.fishsunny.assistant.plug.world.entity;

/*
 * @Usage 世界观下的群组角色实体（id 主键，世界内 name 唯一）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class WorldCharacter {

    /** 角色 ID（主键，稳定标识，改名不变） */
    private String id;

    /** 所属世界观 ID */
    private String worldId;

    /** 角色名称（同一世界观内唯一） */
    private String name;

    /** AI 模型配置（JSON 字符串：adapterName + model + 扩展参数，不含设定文本） */
    private String aiSettings;

    /** 角色设定 */
    private String setting;

    /** 简介 */
    private String intro;

    /** 头像（base64 压缩存储） */
    private String avatar;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public WorldCharacter() {
    }
}
