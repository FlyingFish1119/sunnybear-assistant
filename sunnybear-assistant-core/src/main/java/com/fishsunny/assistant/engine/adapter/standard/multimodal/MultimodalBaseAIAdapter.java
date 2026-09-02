package com.fishsunny.assistant.engine.adapter.standard.multimodal;

/*
 * @Usage 多模态 tool 结果协议的基础适配器（OpenAI 兼容格式）。
 *        与 Standard 适配器完全解耦：直接继承 AIAdapter，自带完整转换逻辑，
 *        唯一差异是 tool 消息 content 支持多模态数组（text + image_url + input_audio）。
 *        即使本链路出错也不会影响 Standard 协议。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/2
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalSystemMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalToolMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role.MultimodalUserMessage;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.role.StandardAssistantMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.role.StandardToolMessage;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.role.StandardUserMessage;
import com.fishsunny.assistant.engine.protocol.standard.content.StandardContent;
import com.fishsunny.assistant.engine.protocol.standard.content.audio.StandardAudioContent;
import com.fishsunny.assistant.engine.protocol.standard.content.image.StandardImageContent;
import com.fishsunny.assistant.engine.protocol.standard.content.text.StandardTextContent;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequestFunction;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.ObjectMapperFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class MultimodalBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(MultimodalBaseAIAdapter.class);
    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    public MultimodalBaseAIAdapter(AIAdapterOption option) throws Exception {
        super(option);
    }

    @Override
    protected Stream<String> establishHttpClient(AIRequest request) throws Exception {
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(super.baseUrl))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + super.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();

        HttpResponse<Stream<String>> response = super.httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofLines());
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

    // ==================== 请求侧：ChatMessage → MultimodalMessage ====================

    /**
     * 把 ChatMessage 列表转换为 MultimodalMessage 列表。
     * tool 消息的 contents 含非文本内容（图片/音频，来自多模态工具结果）时，
     * content 渲染为 content 数组；否则退回纯文本 String 形式，兼容只支持字符串的端点。
     */
    protected List<MultimodalMessage> convertToMultimodalMessage(List<ChatMessage> messages) {
        List<MultimodalMessage> multimodalMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message.getRole()) {
                case "user":
                    MultimodalUserMessage userMessage = new MultimodalUserMessage();
                    userMessage.setContent(convertToStandardContent(message.getContents()));
                    multimodalMessages.add(userMessage);
                    break;
                case "assistant":
                    MultimodalAssistantMessage assistantMessage =
                            new MultimodalAssistantMessage(message.resolveText(), message.getReasoningContent());
                    List<StandardToolRequest> toolRequests = new ArrayList<>();
                    for (ChatToolRequest toolRequest : message.getToolCalls()) {
                        StandardToolRequest standardToolRequest = new StandardToolRequest();
                        standardToolRequest.setId(toolRequest.getId());
                        standardToolRequest.setFunction(new StandardToolRequestFunction()
                                .setName(toolRequest.getName()).setArguments(toolRequest.getArguments()));
                        toolRequests.add(standardToolRequest);
                    }
                    assistantMessage.setTool_calls(toolRequests.isEmpty() ? null : toolRequests);
                    multimodalMessages.add(assistantMessage);
                    break;
                case "system":
                    multimodalMessages.add(new MultimodalSystemMessage(message.resolveText()));
                    break;
                case "tool":
                    multimodalMessages.add(convertToMultimodalToolMessage(message));
                    break;
                default:
                    throw new RuntimeException("Invalid role: " + message.getRole());
            }
        }
        return multimodalMessages;
    }

    /**
     * tool 消息转换：content 始终渲染为数组（纯文本也是 text part）。
     * 转换前先经 {@link MessageContent#fillFiles} 归一化——同轮内存中的多模态工具结果是文件路径，
     * 需转成 data URI 才能被 OpenAI image_url 识别；跨轮重载的已是 data URI，fillFiles 会透传。
     */
    protected MultimodalToolMessage convertToMultimodalToolMessage(ChatMessage message) {
        List<MessageContent> contents = message.getContents();
        if (contents == null || contents.isEmpty()) {
            return new MultimodalToolMessage(message.getToolCallId(), List.of());
        }
        List<MessageContent> normalized = MessageContent.fillFiles(contents);
        return new MultimodalToolMessage(message.getToolCallId(), convertToStandardContent(normalized));
    }

    // ==================== 响应侧：Standard 响应 → ChatMessage ====================
    // 响应线格式与 OpenAI 标准协议字节级一致，直接复用 Standard 响应数据类（只读，无耦合），
    // 仅在适配器侧做转换，因此这里接收 List<StandardMessage>。

    protected List<ChatMessage> convertToChatMessage(List<StandardMessage> standardMessages) {
        List<ChatMessage> messages = new ArrayList<>();
        for (StandardMessage standardMessage : standardMessages) {
            if (standardMessage instanceof StandardUserMessage standardUserMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole("user");
                message.setContents(convertToMessageContent(standardUserMessage.getContent()));
                messages.add(message);
                continue;
            }
            if (standardMessage instanceof StandardAssistantMessage standardAssistantMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole("assistant")
                        .text(standardAssistantMessage.getContent())
                        .setReasoningContent(standardAssistantMessage.getReasoning_content());

                List<ChatToolRequest> toolCalls = new ArrayList<>();
                for (StandardToolRequest toolCallRequest : standardAssistantMessage.getTool_calls()) {
                    ChatToolRequest masterToolCallRequest = new ChatToolRequest();
                    masterToolCallRequest.setId(toolCallRequest.getId());
                    masterToolCallRequest.setName(toolCallRequest.getFunction().getName());
                    if (toolCallRequest.getFunction().getArguments() != null) {
                        masterToolCallRequest.setArguments(
                                (masterToolCallRequest.getArguments() == null ? "" : masterToolCallRequest.getArguments())
                                        + toolCallRequest.getFunction().getArguments());
                    }
                    toolCalls.add(masterToolCallRequest);
                }
                message.setToolCalls(toolCalls);
                messages.add(message);
                continue;
            }
            if (standardMessage instanceof StandardToolMessage toolMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole("tool")
                        .setToolCallId(toolMessage.getTool_call_id())
                        .text(toolMessage.getContent());
                messages.add(message);
                continue;
            }
        }
        return messages;
    }

    // ==================== Content 转换（纯文本/图片/音频 → content part） ====================

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
                log.warn("当前多模态协议(OpenAI)不支持视频内容，已跳过: {}", videoContent.getUrl());
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
                String format = audioContent.getInput_audio().getFormat();
                String mimeType = Base64Utils.getMimeTypeByExtension(format);
                String dataUri = "data:" + mimeType + ";base64," + audioContent.getInput_audio().getData();
                contents.add(new AudioContent(dataUri));
            }
        }
        return contents;
    }
}
