package com.fishsunny.assistant.engine.protocol.project.entity.message;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 02:23
 */

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.utils.ObjectUtils;
import jakarta.annotation.Nullable;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ChatMessage {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_TOOL = "tool";

    private String sessionId;

    private String id;

    private String parentId;

    // role = "tool"
    private String  toolCallId;

    // 助手名 / 用户名 / 工具名
    private String name;

    // system / user / assistant / tool
    private String role;

    private String reasoningContent;

    private List<MessageContent> contents = new ArrayList<>();

    // role = "assistant"
    private List<ChatToolRequest> toolCalls = new ArrayList<>();

    private Map<String, Object> extension = new HashMap<>();

    private Boolean active;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    /** Anthropic extended thinking 推理签名，回传思考内容时必需 */
    private String reasoningSignature;

    /** 同父节点下的兄弟节点总数（包括自己），查询时动态计算，不持久化 */
    private transient Integer siblingCount;

    /** 自己在兄弟节点中的排序位置（按 create_time 升序，从 0 开始），查询时动态计算，不持久化 */
    private transient Integer siblingIndex;

    /** 是否可以插入 */
    @Setter(AccessLevel.NONE)
    private transient boolean canInsert;

    public ChatMessage() {
    }

    public ChatMessage tool(String toolCallId, String result) {
        this.toolCallId = toolCallId;
        this.role = ROLE_TOOL;
        text(result);
        return this;
    }

    public ChatMessage user(String text) {
        this.role = ROLE_USER;
        text(text);
        return this;
    }
    public ChatMessage user(String text, MessageContent content) {
        this.role = ROLE_USER;
        text(text);
        this.contents.add(content);
        return this;
    }
    public ChatMessage user(String text, List<MessageContent> contents) {
        this.role = ROLE_USER;
        text(text); 
        this.contents.addAll(contents);
        return this;
    }
    public ChatMessage userWithImage(String text, String imageUrl) {
        this.role = ROLE_USER;
        text(text);
        this.contents.add(new ImageContent(imageUrl));
        return this;
    }
    public ChatMessage userWithVideo(String text, String videoUrl) {
        this.role = ROLE_USER;
        text(text);
        this.contents.add(new VideoContent(videoUrl));
        return this;
    }

    public ChatMessage system(String text) {
        this.role = ROLE_SYSTEM;
        text(text);
        return this;
    }
    
    public ChatMessage assistant(String text, String reasoningContent, List<ChatToolRequest> toolCalls) {
        this.role = ROLE_ASSISTANT;
        text(text);
        this.reasoningContent = reasoningContent;
        this.toolCalls = toolCalls == null ? new ArrayList<>() : toolCalls;
        return this;
    }

    public ChatMessage text(String text) {
        List<MessageContent> contents = new ArrayList<>();
        contents.add(new TextContent(text));
        this.contents = contents;
        return this;
    }

    public String resolveText() {
        if (contents == null) {
            throw new IllegalArgumentException("contents cannot be null");
        }

        StringBuilder text = new StringBuilder();
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                String append = textContent.getContent() == null ? "" : textContent.getContent();
                text.append(append).append("\n\n");
            }
        }
        return text.toString();
    }

    public ChatMessage makeInsertable(String chatSessionId, @Nullable String parentId, @Nullable String name) {
        if (! StringUtils.hasText(this.role)) {
            throw new IllegalArgumentException("role cannot be null");
        }
        switch (this.role) {
            case ROLE_USER:
            case ROLE_ASSISTANT:
            case ROLE_SYSTEM:
            case ROLE_TOOL:
                break;
            default:
                throw new IllegalArgumentException("Invalid role: " + this.role);
        }
        if (this.role.equals(ROLE_SYSTEM)) {
            throw new IllegalArgumentException("System messages cannot be inserted");
        }

        if (this.role.equals(ROLE_TOOL) && !StringUtils.hasText(this.toolCallId)) {
            throw new IllegalArgumentException("toolCallId cannot be null when role is TOOL");
        }
        if (!this.role.equals(ROLE_ASSISTANT) && !CollectionUtils.isEmpty(this.toolCalls)) {
            throw new IllegalArgumentException("toolCalls have elements when role is not ASSISTANT");
        }
        this.sessionId = chatSessionId;
        this.parentId = parentId;
        this.name = name;
        this.canInsert = true;
        return this;
    }

    public static List<ChatMessage> fillAllFile(List<ChatMessage> messages) {
        List<ChatMessage> resultMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            List<MessageContent> contents = MessageContent.fillFiles(message.getContents());
            message.setContents(contents);
            resultMessages.add(message);
        }
        return resultMessages;
    }

    public static String getParentId(List<ChatMessage> originMessages) {
        ChatMessage last = ObjectUtils.getLast(originMessages);
        return last != null ? last.getId() : null;
    }
}
