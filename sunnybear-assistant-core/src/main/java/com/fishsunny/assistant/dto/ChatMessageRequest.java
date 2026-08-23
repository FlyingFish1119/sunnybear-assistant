package com.fishsunny.assistant.dto;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 03:28
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.exception.UserException;
import lombok.Data;
import lombok.experimental.Accessors;
import org.springframework.util.StringUtils;

import java.util.List;

@Data
@Accessors(chain = true)
public class ChatMessageRequest {

    private static final String TEMP = "temp_";

    public static final String MODE_CREATE = "create";
    public static final String MODE_APPEND = "append";
    public static final String MODE_REPLACE = "replace";
    public static final String MODE_EDIT = "edit";
    public static final String MODE_TEMP_WHAT_IS_THIS = TEMP + "what_is_this";

    private String sessionId;

    /**
     * 消息模式: create(新建) / append(追加) / replace(替换) / edit(编辑用户消息)
     */
    private String mode;

    /** cron 任务 ID，不为空时表示该请求来自 cron 定时触发 */
    private Integer cronId;

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

    public ChatMessageRequest parseAndValidate(String payload, ObjectMapper objectMapper) {
        try {
            ChatMessageRequest request = objectMapper.readValue(payload, ChatMessageRequest.class);
            switch (request.getMode()) {
                case ChatMessageRequest.MODE_TEMP_WHAT_IS_THIS:
                case ChatMessageRequest.MODE_CREATE:
                    if (!StringUtils.hasText(request.getContent())) {
                        throw new UserException("内容为空");
                    }
                    return request;
                case ChatMessageRequest.MODE_APPEND:
                case ChatMessageRequest.MODE_REPLACE:
                case ChatMessageRequest.MODE_EDIT:
                    if (!StringUtils.hasText(request.getContent())) {
                        throw new UserException("内容为空");
                    }
                    if (!StringUtils.hasText(request.getSessionId())) {
                        throw new UserException("会话 ID 为空");
                    }
                    return request;
                default:
                    throw new UserException("无效的请求类型[" + request.getMode() + "]");
            }
        } catch (Exception e) {
            throw new UserException("消息格式无效: " + e.getMessage());
        }
    }

    public boolean isTemp() {
        return this.mode.startsWith(TEMP);
    }
}
