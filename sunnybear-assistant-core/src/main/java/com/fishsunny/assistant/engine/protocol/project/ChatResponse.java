package com.fishsunny.assistant.engine.protocol.project;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 16:33
 */

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.*;

@Data
@Accessors(chain = true)
public class ChatResponse implements AIResponse {

    public final static String STATUS_CHUNK = "chunk";
    public final static String STATUS_DONE = "done";

    public final static String STATUS_ERROR = "error";
    public final static String STATUS_INIT_USER = "init_user";
    public final static String STATUS_INIT_ASSISTANT = "init_assistant";
    public final static String STATUS_TOOL_RESPONSE = "tool_response";
    public final static String STATUS_TEMP = "temp";
    public final static String STATUS_TEMP_CHUNK = "temp_chunk";

    private String sessionId;

    private String status;

    private List<ChatMessage> messages = new ArrayList<>();

    public ChatResponse setMessages(List<ChatMessage> messages) {
        this.messages = messages == null ? new ArrayList<>() : messages;
        return this;
    }

    private Map<String, Object> metadata = new HashMap<>();

    public ChatResponse setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new HashMap<>() : metadata;
        return this;
    }

    public ChatResponse() {
    }

    public ChatResponse afterAIResponse(ChatMessage message) {
        this.status = STATUS_INIT_ASSISTANT;
        this.messages = List.of(message);
        this.sessionId = message.getSessionId();
        return this;
    }

    public ChatResponse afterUserInput(ChatMessage message) {
        this.status = STATUS_INIT_USER;
        this.messages = List.of(message);
        this.sessionId = message.getSessionId();
        return this;
    }

    public ChatResponse afterToolCall(List<ChatMessage> message) {
        this.status = STATUS_TOOL_RESPONSE;
        this.messages = new ArrayList<>(message);
        if (!message.isEmpty()) {
            this.sessionId = message.getFirst().getSessionId();
        }
        return this;
    }

    public ChatResponse afterError(String sessionId, String errorMes) {
        this.status = STATUS_ERROR;
        this.messages = List.of(new ChatMessage().assistant(errorMes, null, null));
        this.sessionId = sessionId;
        return this;
    }

    public ChatResponse afterTemp(ChatResponse response) {
        switch (response.getStatus()) {
            case STATUS_CHUNK -> this.status = STATUS_TEMP_CHUNK;
            case STATUS_DONE -> this.status = STATUS_TEMP;
            default -> throw new RuntimeException("Invalid status: " + response.getStatus());
        }
        setMessages(response.getMessages());
        setMetadata(response.getMetadata());
        this.sessionId = response.getSessionId();
        return this;
    }
    public ChatResponse afterTemp(String content, String reasoningContent) {
        this.status = STATUS_TEMP;
        this.messages = List.of(new ChatMessage().assistant(content, reasoningContent, List.of()));
        this.sessionId = UUID.randomUUID().toString();
        return this;
    }

    public String getText() {
        StringBuilder stringBuilder = new StringBuilder();
        for (ChatMessage message : messages) {
            for (MessageContent content : message.getContents()) {
                // 注意：不能用 StringUtils.hasText() 过滤，否则流式传输时纯空白 chunk（如 \n\n 段落分隔）会被丢弃
                if (content instanceof TextContent textContent && textContent.getContent() != null) {
                    stringBuilder.append(textContent.getContent());
                }
            }
        }
        return stringBuilder.isEmpty() ? null : stringBuilder.toString();
    }

    public void appendTextAtStart(String text) {
        for (ChatMessage message : messages) {
            for (MessageContent content : message.getContents()) {
                if (content instanceof TextContent textContent && textContent.getContent() != null) {
                    textContent.setContent(text + textContent.getContent());
                }
            }
        }
    }

    public String getReasoningContent() {
        StringBuilder stringBuilder = new StringBuilder();
        for (ChatMessage message : messages) {
            if (message.getReasoningContent() != null) {
                stringBuilder.append(message.getReasoningContent());
            }
        }
        return stringBuilder.isEmpty() ? null : stringBuilder.toString();
    }
}
