package com.fishsunny.assistant.engine.protocol.project.entity.message;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 02:23
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.variable.RoleVariable;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.experimental.Accessors;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ChatMessage {

    private String sessionId;

    private String id;

    private String parentId;

    // role = "tool"
    private String toolCallId;

    // 助手名 / 用户名 / 工具名
    private String name;

    // system / user / assistant / tool
    private String role;

    private String reasoningContent;

    /** Anthropic extended thinking 推理签名，回传思考内容时必需 */
    private String reasoningSignature;

    private List<MessageContent> contents = new ArrayList<>();

    // role = "assistant"
    private List<ChatToolRequest> toolCalls = new ArrayList<>();

    private Map<String, Object> extension = new HashMap<>();

    private Boolean active;

    /** 同父节点下的兄弟节点总数（包括自己），查询时动态计算，不持久化 */
    private transient Integer siblingCount;

    /** 自己在兄弟节点中的排序位置（按 create_time 升序，从 0 开始），查询时动态计算，不持久化 */
    private transient Integer siblingIndex;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    public ChatMessage() {
    }

    public ChatMessage tool(String sessionId, String parentId, String toolCallId,
                     String toolName, String result) {
        this.sessionId = sessionId;
        this.parentId = parentId;
        this.toolCallId = toolCallId;
        this.name = toolName;
        this.role = RoleVariable.ROLE_TOOL;

        List<MessageContent> contents = new ArrayList<>();
        contents.add(new TextContent(result));
        this.contents = contents;

        return this;
    }

    public ChatMessage user(String text) {
        this.role = RoleVariable.ROLE_USER;
        this.contents = new ArrayList<>();
        this.contents.add(new TextContent(text));
        return this;
    }

    public ChatMessage user(String text, String imageUrl) {
        this.role = RoleVariable.ROLE_USER;
        this.contents = new ArrayList<>();
        this.contents.add(new TextContent(text));
        this.contents.add(new ImageContent(imageUrl));
        return this;
    }

    public ChatMessage user(String text, MessageContent content) {
        this.role = RoleVariable.ROLE_USER;
        this.contents = new ArrayList<>();
        this.contents.add(new TextContent(text));
        this.contents.add(content);
        return this;
    }

    public ChatMessage user(String text, List<MessageContent> contents) {
        this.role = RoleVariable.ROLE_USER;
        this.contents = new ArrayList<>();
        this.contents.add(new TextContent(text));
        this.contents.addAll(contents);
        return this;
    }

    public ChatMessage system(String text) {
        this.role = RoleVariable.ROLE_SYSTEM;
        text(text);
        return this;
    }


    public ChatMessage assistant(String text, String reasoningContent, List<ChatToolRequest> toolCalls) {
        this.role = RoleVariable.ROLE_ASSISTANT;
        text(text);
        this.reasoningContent = reasoningContent;
        this.toolCalls = toolCalls;
        return this;
    }

    public ChatMessage tool(String toolCallId, String text) {
        this.role = RoleVariable.ROLE_TOOL;
        this.toolCallId = toolCallId;
        text(text);
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

    public static List<ChatMessage> fillAllFile(List<ChatMessage> messages) {
        List<ChatMessage> resultMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            List<MessageContent> contents = MessageContent.loadContentFile(message.getContents());
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
