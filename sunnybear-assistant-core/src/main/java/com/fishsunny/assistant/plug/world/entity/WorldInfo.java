package com.fishsunny.assistant.plug.world.entity;

/*
 * @Usage 世界观信息实体（核心世界观 + 整体配置）
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
public class WorldInfo {

    private String id;

    /** 世界观名称 */
    private String name;

    /** 世界观描述 */
    private String description;

    /** 预设（每次对话时拼接到世界观设定之前发送） */
    private String preset;

    /** 背景图文件路径（通过 file proxy 访问） */
    private String background;

    /** 世界观主题色 */
    private String mainColor;

    /** 旁白启用 */
    private Boolean narrationEnable;

    /** 玩家夺舍的角色 name，空串 = 不夺舍 */
    private String possessName;

    /** 每轮最大轮数 */
    private Integer maxRounds;

    /** 调度器 AI 配置（JSON 字符串：adapterName/model，仅这两项） */
    private String schedulerAiSettings;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public WorldInfo() {
    }
}
