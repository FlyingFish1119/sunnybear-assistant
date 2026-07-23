package com.fishsunny.assistant.dto;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 03:28
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class ChatMessageRequest {

    public static final String MODE_CREATE = "create";
    public static final String MODE_APPEND = "append";
    public static final String MODE_REPLACE = "replace";
    public static final String MODE_EDIT = "edit";

    private String sessionId;

    /**
     * 消息模式: create(新建) / append(追加) / replace(替换) / edit(编辑用户消息)
     */
    private String mode;

    private String content;

    /**
     * replace 模式专用：要被替换的助手消息 ID
     */
    private String replaceMessageId;

    /**
     * edit 模式专用：要被编辑的用户消息 ID
     */
    private String editMessageId;

    private List<FileData> files;

    public ChatMessageRequest() {
    }
}
