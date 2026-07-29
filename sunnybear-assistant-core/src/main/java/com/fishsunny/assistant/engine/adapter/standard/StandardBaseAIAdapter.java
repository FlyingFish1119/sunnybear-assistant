package com.fishsunny.assistant.engine.adapter.standard;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 05:52
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardSystemMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardToolMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.StandardUserMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.StandardContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.audio.StandardAudioContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.image.StandardImageContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.text.StandardTextContent;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequestFunction;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import com.fishsunny.assistant.variable.RoleVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class StandardBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(StandardBaseAIAdapter.class);
    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    public StandardBaseAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    public AIRequest convertToTarget(AIRequest request) {
        return null;
    }

    @Override
    public AIRequest convertToMaster(AIRequest request) {
        return null;
    }

    @Override
    public AIResponse convertToTarget(AIResponse response) {
        return null;
    }

    @Override
    public AIResponse convertToMaster(AIResponse response) {
        return null;
    }

    @Override
    protected Stream<String> establishHttpClient(AIRequest request) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(super.baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + super.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();

        HttpResponse<Stream<String>> response = this.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
        if (response.statusCode() != 200) {
            try (Stream<String> bodyStream = response.body()) {
                String errorMessage = bodyStream.collect(Collectors.joining("\n"));
                log.info(errorMessage);
                throw new RuntimeException("Invalid status code: " + response.statusCode() + ", error: " + errorMessage);
            }
        } else {
            return response.body();
        }
    }

    @Override
    public boolean finished(AIResponse response) {
        return false;
    }

    @Override
    public boolean collectChunk(AIResponse response) {
        return false;
    }

    @Override
    public List<ToolCall> getToolCalls() {
        return List.of();
    }

    @Override
    public void checkCls(Class<? extends AIRequest> masterCls, Class<? extends AIRequest> targetCls, Class<? extends AIResponse> masterRespCls, Class<? extends AIResponse> targetRespCls) throws Exception {

    }


    protected List<StandardMessage> convertToStandardMessage(List<ChatMessage> messages) {
        List<StandardMessage> standardMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message.getRole()) {
                case RoleVariable.ROLE_USER:
                    StandardUserMessage standardUserMessage = new StandardUserMessage();
                    standardUserMessage.setContent(convertToStandardContent(message.getContents()));
                    standardMessages.add(standardUserMessage);
                    break;
                case RoleVariable.ROLE_ASSISTANT:
                    StandardAssistantMessage standardAssistantMessage = new StandardAssistantMessage(message.resolveText(), message.getReasoningContent());
                    standardMessages.add(standardAssistantMessage);
                    List<StandardToolRequest> standardToolRequests = new ArrayList<>();
                    for (ChatToolRequest toolRequest : message.getToolCalls()) {
                        StandardToolRequest standardToolRequest = new StandardToolRequest();
                        standardToolRequest.setId(toolRequest.getId());
                        standardToolRequest.setFunction(new StandardToolRequestFunction()
                                .setName(toolRequest.getName()).setArguments(toolRequest.getArguments()));
                        standardToolRequests.add(standardToolRequest);
                    }
                    standardAssistantMessage.setTool_calls(standardToolRequests.isEmpty() ? null : standardToolRequests);
                    break;
                case RoleVariable.ROLE_SYSTEM:
                    StandardSystemMessage standardSystemMessage = new StandardSystemMessage(message.resolveText());
                    standardMessages.add(standardSystemMessage);
                    break;
                case RoleVariable.ROLE_TOOL:
                    StandardToolMessage standardToolMessage = new StandardToolMessage(message.getToolCallId(), message.resolveText());
                    standardMessages.add(standardToolMessage);
                    break;
                default:
                    throw new RuntimeException("Invalid role: " + message.getRole());
            }
        }
        return standardMessages;
    }

    protected List<ChatMessage> convertToChatMessage(List<StandardMessage> standardMessages) {
        List<ChatMessage> messages = new ArrayList<>();
        for (StandardMessage standardMessage : standardMessages) {
            if (standardMessage instanceof StandardUserMessage standardUserMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole(RoleVariable.ROLE_USER);
                message.setContents(convertToMessageContent(standardUserMessage.getContent()));
                messages.add(message);
                continue;
            }
            if (standardMessage instanceof StandardAssistantMessage standardAssistantMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole(RoleVariable.ROLE_ASSISTANT)
                        .text(standardAssistantMessage.getContent())
                        .setReasoningContent(standardAssistantMessage.getReasoning_content());

                List<ChatToolRequest> toolCalls = new ArrayList<>();
                for (StandardToolRequest toolCallRequest : standardAssistantMessage.getTool_calls()) {
                    ChatToolRequest masterToolCallRequest = new ChatToolRequest();
                    masterToolCallRequest.setId(toolCallRequest.getId());
                    masterToolCallRequest.setName(toolCallRequest.getFunction().getName());
                    if (toolCallRequest.getFunction().getArguments() != null) {
                        // 防御性检测
                        masterToolCallRequest.setArguments(masterToolCallRequest.getArguments() == null ? "" : masterToolCallRequest.getArguments());
                        masterToolCallRequest.setArguments(masterToolCallRequest.getArguments() + toolCallRequest.getFunction().getArguments());
                    }
                    toolCalls.add(masterToolCallRequest);
                }
                message.setToolCalls(toolCalls);
                messages.add(message);
                continue;
            }
            if (standardMessage instanceof StandardToolMessage toolMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole(RoleVariable.ROLE_TOOL)
                        .setToolCallId(toolMessage.getTool_call_id())
                        .text(toolMessage.getContent());
                messages.add(message);
                continue;
            }
        }
        return messages;
    }

    protected List<StandardContent> convertToStandardContent(List<MessageContent> contents) {
        List<StandardContent> standardContents = new ArrayList<>();
        for (MessageContent content : contents) {
            if (content instanceof TextContent textContent) {
                standardContents.add(new StandardTextContent(textContent.getContent()));
                continue;
            }
            if (content instanceof ImageContent imageContent) {
                standardContents.add(new StandardImageContent(imageContent.getUrl()));
                continue;
            }
            if (content instanceof AudioContent audioContent) {
                // audioContent.getUrl() 此时是 data URI: data:audio/mpeg;base64,{raw}
                String dataUri = audioContent.getUrl();
                String base64Data = Base64Utils.extractBase64FromDataUri(dataUri);
                String format = Base64Utils.getExtensionFromDataUri(dataUri);
                if (base64Data != null) {
                    standardContents.add(new StandardAudioContent(base64Data, format));
                } else {
                    log.warn("无法解析音频 data URI，跳过该音频内容");
                }
                continue;
            }
            if (content instanceof VideoContent videoContent) {
                // OpenAI Chat Completions API 不支持 video_url 类型，跳过并记录日志
                log.warn("当前标准协议(OpenAI)不支持视频内容，已跳过: {}", videoContent.getUrl());
            }
        }
        return standardContents;
    }

    protected List<MessageContent> convertToMessageContent(List<StandardContent> standardContents) {
        List<MessageContent> contents = new ArrayList<>();
        for (StandardContent standardContent : standardContents) {
            if (standardContent instanceof StandardTextContent textContent) {
                contents.add(new TextContent(textContent.getText()));
                continue;
            }
            if (standardContent instanceof StandardImageContent imageContent) {
                contents.add(new ImageContent(imageContent.getImage_url().getUrl()));
                continue;
            }
            if (standardContent instanceof StandardAudioContent audioContent) {
                // 将 OpenAI 格式 (base64 + format) 重构为 data URI
                String format = audioContent.getInput_audio().getFormat();
                String mimeType = Base64Utils.getMimeTypeByExtension(format);
                String dataUri = "data:" + mimeType + ";base64," + audioContent.getInput_audio().getData();
                contents.add(new AudioContent(dataUri));
            }
        }
        return contents;
    }
}
