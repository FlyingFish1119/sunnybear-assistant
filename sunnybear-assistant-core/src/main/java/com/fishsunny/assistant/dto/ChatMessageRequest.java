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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
    public ChatMessageRequest setMode(String mode) {
        if (!StringUtils.hasText(mode)) {
            throw new UserException("请求类型为空");
        }
        switch (mode) {
            case MODE_CREATE:
            case MODE_APPEND:
            case MODE_REPLACE:
            case MODE_EDIT:
            case MODE_TEMP_WHAT_IS_THIS:
                break;
            default:
                throw new UserException("无效的请求类型[" + mode + "]");
        }
        this.mode = mode;
        return this;
    }

    /** cron 任务 ID，不为空时表示该请求来自 cron 定时触发 */
    private Integer cronId;

    /**
     * replace 模式专用：要被替换的助手消息 ID
     */
    private String replaceMessageId;
    public ChatMessageRequest setReplaceMessageId(String replaceMessageId) {
        this.replaceMessageId = replaceMessageId == null ? "" : replaceMessageId;
        return this;
    }

    /**
     * edit 模式专用：要被编辑的用户消息 ID
     */
    private String editMessageId;
    public ChatMessageRequest setEditMessageId(String editMessageId) {
        this.editMessageId = editMessageId == null ? "" : editMessageId;
        return this;
    }

    private String content;

    private List<FileData> files;
    public ChatMessageRequest setFiles(List<FileData> files) {
        this.files = files == null ? new ArrayList<>() : files;
        return this;
    }

    /**
     * 新会话的初始 extension —— 落点是 {@code ChatSession.extension}，<b>不是消息的 extension</b>。
     *
     * <p>只有 create 模式读它：新建会话那会儿会话还没落库，没有 mergeExtension 的对象，
     * 只能借 create 请求把这包键值整体送进去（见 CreateChatMessageRequestHandler#createChatSession）。
     * append / edit / replace 三个模式完全不看这个字段。
     *
     * <p>键的语义与登记见 extension-filed-record/CHAT_SESSION_EXTENSION.md
     * （worldId / characterId / chat_project_id 都走这条路）。
     */
    private Map<String, Object> extension;
    public ChatMessageRequest setExtension(Map<String, Object> extension) {
        this.extension = extension == null ? new HashMap<>() : extension;
        return this;
    }

    /**
     * 本条用户消息的 extension —— 落点是 {@code ChatMessage.extension}，键由调用方自定义。
     *
     * <p>create / append / edit 三种模式读它：这三种都会新建一条用户消息，值在落库前挂好；
     * replace 只是停用旧助手分支、不新建用户消息，因此不读。
     *
     * <p>与上面那个 {@link #extension} 是两个不同的口袋：那个进会话，这个进消息。
     * 后端自己写的那些消息键（chat_usage / ttsAudio / reasoningSignature 等）
     * 见 extension-filed-record/CHAT_MESSAGE_EXTENSION.md。
     */
    private Map<String, Object> messageExtension;
    public ChatMessageRequest setMessageExtension(Map<String, Object> messageExtension) {
        this.messageExtension = messageExtension == null ? new HashMap<>() : messageExtension;
        return this;
    }

    /**
     * 本消息请求 AI 回复朗读（TTS）：true 时逐句音频经 ###TTS_AUDIO### 帧随文本推送，
     * 整轮完整音频随 assistant 消息 extension 落库；false / null 不朗读
     */
    private Boolean tts = false;

    /**
     * 新会话模型档位强制指定（仅 create 模式有意义）：
     * true = 强制高级、false = 强制普通、null / 不传 = 不干预，
     * 不干预时交由服务端按「自动切换模型」判断复杂度决定（见 CreateChatMessageRequestHandler）
     */
    private Boolean forcePro;

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
