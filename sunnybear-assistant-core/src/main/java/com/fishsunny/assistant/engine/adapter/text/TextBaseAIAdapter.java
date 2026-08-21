package com.fishsunny.assistant.engine.adapter.text;

/*
 * @Usage Text 协议基础适配器，content 为 String（非数组），比 Standard 协议更简单
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 10:30
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.adapter.AIAdapterOption;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequestFunction;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextAssistantMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextSystemMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextToolMessage;
import com.fishsunny.assistant.engine.protocol.text.messages.role.TextUserMessage;
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

public abstract class TextBaseAIAdapter extends AIAdapter {

    protected static final Logger log = LoggerFactory.getLogger(TextBaseAIAdapter.class);
    protected final ObjectMapper objectMapper = ObjectMapperFactory.getObjectMapper();

    public TextBaseAIAdapter(AIAdapterOption option) throws Exception {
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
    public void checkCls(Class<? extends AIRequest> masterCls,
                         Class<? extends AIRequest> targetCls,
                         Class<? extends AIResponse> masterRespCls,
                         Class<? extends AIResponse> targetRespCls) throws Exception {
    }

    /**
     * 将 ChatMessage 列表转换为 TextMessage 列表。
     * Text 协议的 content 是 String，比 Standard 协议的数组形式更简单。
     */
    protected List<TextMessage> convertToTextMessage(List<ChatMessage> messages) {
        List<TextMessage> textMessages = new ArrayList<>();
        for (ChatMessage message : messages) {
            switch (message.getRole()) {
                case "user":
                    textMessages.add(new TextUserMessage(message.resolveText()));
                    break;
                case "assistant":
                    TextAssistantMessage assistantMessage = new TextAssistantMessage(message.resolveText(), message.getReasoningContent());
                    List<StandardToolRequest> standardToolRequests = new ArrayList<>();
                    for (ChatToolRequest toolRequest : message.getToolCalls()) {
                        StandardToolRequest standardToolRequest = new StandardToolRequest();
                        standardToolRequest.setId(toolRequest.getId());
                        standardToolRequest.setFunction(new StandardToolRequestFunction()
                                .setName(toolRequest.getName())
                                .setArguments(toolRequest.getArguments()));
                        standardToolRequests.add(standardToolRequest);
                    }
                    assistantMessage.setTool_calls(standardToolRequests.isEmpty() ? null : standardToolRequests);
                    textMessages.add(assistantMessage);
                    break;
                case "system":
                    textMessages.add(new TextSystemMessage(message.resolveText()));
                    break;
                case "tool":
                    textMessages.add(new TextToolMessage(message.getToolCallId(), message.resolveText()));
                    break;
                default:
                    throw new RuntimeException("Invalid role: " + message.getRole());
            }
        }
        return textMessages;
    }

    /**
     * 将 TextMessage 列表转换回 ChatMessage 列表。
     */
    protected List<ChatMessage> convertToChatMessage(List<TextMessage> textMessages) {
        List<ChatMessage> messages = new ArrayList<>();
        for (TextMessage textMessage : textMessages) {
            if (textMessage instanceof TextUserMessage userMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole("user");
                message.text(userMessage.getContent());
                messages.add(message);
                continue;
            }
            if (textMessage instanceof TextAssistantMessage assistantMessage) {
                ChatMessage message = new ChatMessage();
                message.setRole("assistant")
                        .text(assistantMessage.getContent())
                        .setReasoningContent(assistantMessage.getReasoning_content());

                List<ChatToolRequest> toolCalls = new ArrayList<>();
                if (assistantMessage.getTool_calls() != null) {
                    for (StandardToolRequest toolCallRequest : assistantMessage.getTool_calls()) {
                        ChatToolRequest masterToolCallRequest = new ChatToolRequest();
                        masterToolCallRequest.setId(toolCallRequest.getId());
                        masterToolCallRequest.setName(toolCallRequest.getFunction().getName());
                        if (toolCallRequest.getFunction().getArguments() != null) {
                            masterToolCallRequest.setArguments(masterToolCallRequest.getArguments() == null ? "" : masterToolCallRequest.getArguments());
                            masterToolCallRequest.setArguments(masterToolCallRequest.getArguments() + toolCallRequest.getFunction().getArguments());
                        }
                        toolCalls.add(masterToolCallRequest);
                    }
                }
                message.setToolCalls(toolCalls);
                messages.add(message);
                continue;
            }
            if (textMessage instanceof TextToolMessage toolMessage) {
                ChatMessage message = new ChatMessage();
                message.tool(toolMessage.getTool_call_id(), toolMessage.getContent());
                messages.add(message);
                continue;
            }
        }
        return messages;
    }
}
