package com.fishsunny.assistant.dto;

/*
 * @Usage 通用工具询问 DTO，用于工具需要用户确认时向前端发送确认请求
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 06:30
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.UUID;

@Data
@Accessors(chain = true)
public class ToolAsk {
    /** 确认请求的唯一标识 */
    private String id;
    /** 工具名称 */
    private String toolName;
    /** 确认提示消息 */
    private String message;
    /** 超时时间（秒） */
    private Integer timeout;

    public ToolAsk loadInfo(String toolName, String message) {
        this.id = UUID.randomUUID().toString();
        this.toolName = toolName;
        this.message = message;
        return this;
    }

    public ToolAsk expire(Integer timeout) {
        this.timeout = timeout;
        return this;
    }
}
