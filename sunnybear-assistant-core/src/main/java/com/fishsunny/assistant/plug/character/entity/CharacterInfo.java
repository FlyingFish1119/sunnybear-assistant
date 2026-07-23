package com.fishsunny.assistant.plug.character.entity;

/*
 * @Usage 角色信息实体
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;

@Data
@Accessors(chain = true)
public class CharacterInfo {

    private String id;

    /** 角色名称 */
    private String name;

    /** 头像（base64 压缩存储） */
    private String avatar;

    /** 背景图文件路径（通过 file proxy 访问） */
    private String background;

    /** AI 模型参数（JSON 字符串，包含 prompt + adapterName + model + 扩展参数） */
    private String aiSettings;

    /** 预设（独立于 aiSettings，每次对话时拼接到角色设定之前发送） */
    private String preset;

    /** 角色主题色 */
    private String mainColor;

    /** 背景透明度 */
    private Double opacity;

    /** 工具开关（JSON 字符串，格式为 {"toolName": true/false}），列出的工具才会在角色对话中注入 */
    private String tools;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    public CharacterInfo() {
    }

    public CharacterInfo(String name, String aiSettings) {
        this.name = name;
        this.aiSettings = aiSettings;
    }
}
