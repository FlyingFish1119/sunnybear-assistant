package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage 临时单次问答处理器 —— 无工具、无上下文，聚焦单次问答
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/24
 */

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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private final AISettings chatSettings;

    /**
     * 在途请求登记表，元素形如 {wsSessionId}#{mode}。
     * <p>
     * 同一条 WebSocket 连接、同一模式下同时只允许一个 temp 请求在跑。原因：
     * 流式帧里没有请求标识（收尾帧才带 tempId），两路 temp 流交错推给前端时
     * 前端无从分辨哪一帧属于哪次请求，文字会串到同一个展示框里。
     * 与其在前端做无法可靠完成的归属判断，不如在这里直接串行化 —— 后到的请求
     * 立刻回一条提示，不占用 AI 调用。
     */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public TempChatProcessor(ChatHttpHandler chatHttpHandler,
                             ObjectMapper objectMapper,
                             @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                             @Qualifier(AISettings.CHAT) AISettings chatSettings
                             ) {
        this.chatHttpHandler = chatHttpHandler;
        this.chatSettings = chatSettings;
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
    }

    /**
     * 单次问答：无工具、无上下文
     */
    public void chat(ChatMessageRequest requestDto, WebSocketSession session) throws Exception {
        String lockKey = session.getId() + "#" + requestDto.getMode();
        if (!inFlight.add(lockKey)) {
            log.info("temp 请求已在途，拒绝并发 [{}]", lockKey);
            sendTempMessage(session, "上一段还在处理中，等它出结果再试。");
            return;
        }
        try {
            doChat(requestDto, session);
        } finally {
            inFlight.remove(lockKey);
        }
    }

    private void doChat(ChatMessageRequest requestDto, WebSocketSession session) throws Exception {
        ChatRequest request = buildChatRequest(requestDto);

        // 请求级稳定 ID：所有流式 chunk 与收尾消息共用，前端据此分流 temp 流
        String tempId = UUID.randomUUID().toString();

        ChatHttpHandler.InTranslateCallback inTranslateCallback = response -> {
            try {
                // chunk 也带上 tempId：收尾帧本来就带，这里补齐是为了万一将来放开
                // 并发时（或同连接挂多个展示框）前端还有据可依，不白丢这个信息
                ChatResponse tempResp = new ChatResponse().afterTemp((ChatResponse) response).setSessionId(tempId);
                String respJson = objectMapper.writeValueAsString(tempResp);
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
        ChatHttpHandler.TranslateData translateData = new ChatHttpHandler.TranslateData(
                tempId, missionAISettings.getAdapterName(), request
        );
        ChatHttpHandler.TranslateOption translateOption = new ChatHttpHandler.TranslateOption()
                .setStream(chatSettings.getStream())
                .setEnableTTS(false);
        ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler(
                inTranslateCallback, completeCallback
        );

        chatHttpHandler.translate(translateData, translateHandler, translateOption);
    }

    /**
     * 连接关闭时清理该连接名下的登记。
     * <p>
     * 不清也不会串（key 里带会话 id，重连后是新 id），但会留下永不释放的垃圾项。
     */
    public void clearSession(String wsSessionId) {
        String prefix = wsSessionId + "#";
        inFlight.removeIf(key -> key.startsWith(prefix));
    }

    /**
     * 回一条 temp 形状的提示。
     * <p>
     * 刻意复用正常的 temp 通道而不是走全局 error 通道：error 帧前端会当成本轮会话
     * 出错来处理（弹全局提示、按 sessionId 清流式状态），而 temp 请求压根不在会话里，
     * 混进去只会让两边都不干净。走 temp 通道的话，前端小框直接显示这句话就完了。
     */
    private void sendTempMessage(WebSocketSession session, String text) {
        try {
            ChatResponse response = new ChatResponse().afterTemp(text, null);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (Exception e) {
            log.warn("发送 temp 提示失败: {}", e.getMessage());
        }
    }

    /** 超过这个长度就换「概括」而不是「解释」——
     *  解释是给词 / 术语 / 名称准备的，一段话再逐字解释就没意义了，
     *  用户真正想要的是「这段在讲什么」。这条线属于语义边界，跟模型能吃多少 token 无关。 */
    private static final int EXPLAIN_MAX_LENGTH = 200;

    private ChatRequest buildChatRequest(ChatMessageRequest requestDto) {
        String systemPrompt;
        switch (requestDto.getMode()) {
            case ChatMessageRequest.MODE_TEMP_WHAT_IS_THIS:
                systemPrompt = whatIsThis(requestDto.getContent());
                break;
            default:
                throw new IllegalArgumentException("Invalid mode: " + requestDto.getMode());
        }


        return new ChatRequest()
                .loadSettings(missionAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(requestDto.getContent())
                ));
    }

    /**
     * 「这是什么」的提示词：短的当词条解释，长的当段落概括。
     * <p>
     * 分流放在这里，是因为「多长算长」是提示词自己的事 —— 前端不需要知道这条线画在哪，
     * 也就不用在两处各维护一个数字。
     */
    private String whatIsThis(String content) {
        boolean longText = content != null && content.length() > EXPLAIN_MAX_LENGTH;
        return longText ? summarizePrompt() : explainPrompt();
    }

    private String explainPrompt() {
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

    private String summarizePrompt() {
        return """
                你是一个快速的内容概括器。用户会发送一段话、一段文字或一份代码片段，
                你要用最简洁的话把它的核心意思讲清楚。

                要求：
                - 先用一句话点明「这段在讲什么」，再补两到三条关键信息。
                - 控制在 150 字以内。不要复述原文，不要逐句翻译，不要展开评论。
                - 保留原文里的关键名词、数字和结论；原文没说的不要推测补充。
                - 如果原文是代码或技术片段，说明它是做什么的、关键点在哪里。
                - 输出语言与用户输入的语言保持一致。
                """;
    }
}
