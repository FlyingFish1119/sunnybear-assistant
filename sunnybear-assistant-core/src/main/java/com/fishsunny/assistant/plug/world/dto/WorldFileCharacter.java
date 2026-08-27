package com.fishsunny.assistant.plug.world.dto;

/*
 * @Usage 世界观导入/导出文件中的角色（不含头像 base64、ID、时间戳）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class WorldFileCharacter {

    /** 角色名称（同一世界观内唯一） */
    private String name;

    /** 简介 */
    private String intro;

    /** 角色设定 */
    private String setting;

    /** AI 模型配置（对象形式便于阅读和 AI 编辑，导入时兼容字符串形式） */
    private Map<String, Object> aiSettings = new HashMap<>();
}
