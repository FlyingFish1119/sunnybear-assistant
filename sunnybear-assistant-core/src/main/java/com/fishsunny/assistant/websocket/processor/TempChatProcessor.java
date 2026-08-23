package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage 临时单次问答处理器 —— 无工具、无上下文，聚焦单次问答
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/24
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.settings.AISettings;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 临时聊天处理器：接收一条用户消息，使用 mission AI 做单次问答。
 * 没有工具调用、没有历史上下文，适合轻量的一次性问题。
 */
@Component
@Slf4j
public class TempChatProcessor {

    private final ChatHttpHandler chatHttpHandler;
    private final AISettings missionAISettings;
    private final ObjectMapper objectMapper;

    public TempChatProcessor(ChatHttpHandler chatHttpHandler,
                             ObjectMapper objectMapper,
                             @Qualifier(AISettings.MISSION) AISettings missionAISettings) {
        this.chatHttpHandler = chatHttpHandler;
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
    }

    /**
     * 单次问答：无工具、无上下文
     *
     */
    public void chat(ChatMessageRequest requestDto, WebSocketSession session) throws Exception {
        ChatRequest request = buildChatRequest(requestDto);

        // 请求级稳定 ID：所有流式 chunk 与收尾消息共用，前端据此分流 temp 流
        String tempId = UUID.randomUUID().toString();

        ChatHttpHandler.InTranslateCallback inTranslateCallback = response -> {
            ChatResponse chatResponse = (ChatResponse) response;
            chatResponse.setStatus(ChatResponse.STATUS_TEMP_CHUNK).setSessionId(tempId);
            String respJson ;
            try {
                respJson = objectMapper.writeValueAsString(chatResponse);
                session.sendMessage(new TextMessage(respJson));
            } catch (Exception e) {
                log.warn("Failed to write chat response to JSON in inTranslateCallback: {}", e.getMessage());
            }
        };
        ChatHttpHandler.CompleteCallback completeCallback = (result, lastRes) -> {
            ChatResponse response = new ChatResponse().afterTemp(result.content(), result.reasoning())
                    .setSessionId(tempId);
            try {
                String respJson = objectMapper.writeValueAsString(response);
                session.sendMessage(new TextMessage(respJson));
            } catch (Exception e) {
                log.warn("Failed to write chat response to JSON in complete callback: {}", e.getMessage());
            }
        };

        chatHttpHandler.translate(
                UUID.randomUUID().toString(),
                missionAISettings.getAdapterName(),
                request, missionAISettings.getStream(),
                inTranslateCallback,
                completeCallback
        );
    }

    private ChatRequest buildChatRequest(ChatMessageRequest requestDto) {
        String systemPrompt;
        switch (requestDto.getMode()) {
            case ChatMessageRequest.MODE_TEMP_WHAT_IS_THIS:
                systemPrompt = whatIsThis();
                break;
            default:
                throw new IllegalArgumentException("Invalid mode: " + requestDto.getMode());
        }


        return new ChatRequest()
                .loadSettings(missionAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(requestDto.getContent()))
                );
    }

    private String whatIsThis() {
        return """
                你是一个快速的名称解释器。用户会发送一个词、缩写、术语或名词，你要立刻对它做出简洁、准确的解释。

                要求：
                - 解释要简洁，100-200 字左右，直击要点，不要长篇大论。
                - 如果用户发的是缩写或首字母缩略词（如 MMT），先给出最常见的全称，再解释其含义。
                - 如果该词有多种含义，优先解释最可能的一种，可简单提及其他常见含义。
                - 如果遇到专业术语、网络用语或外语词汇，解释其含义、来源和使用场景。
                - 输出语言与用户输入的语言保持一致。
                """;
    }
}
