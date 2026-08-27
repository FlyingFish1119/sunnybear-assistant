package com.fishsunny.assistant.plug.world.dto;

/*
 * @Usage 世界观导入/导出文件中的世界观本体（不含背景图路径，不含 createTime/updateTime）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class WorldFileWorld {

    /** 世界观名称 */
    private String name;

    /** 世界观描述 */
    private String description;

    /** 预设（每次对话时拼接到世界观设定之前发送） */
    private String preset;

    /** 世界观主题色 */
    private String mainColor;

    /** 旁白启用 */
    private Boolean narrationEnable;

    /** 玩家夺舍的角色 name，空串 = 不夺舍 */
    private String possessName;

    /** 每轮最大轮数 */
    private Integer maxRounds;

    /** 调度器 AI 配置（对象形式便于阅读和 AI 编辑，导入时兼容字符串形式） */
    private Map<String, Object> schedulerAiSettings = new HashMap<>();
}
