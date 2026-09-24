package com.fishsunny.assistant.websocket.processor;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.cancel.ChatCancelContext;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatUsage;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.engine.tool.service.security.SecurityService;
import com.fishsunny.assistant.engine.tts.TTSSettings;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.ToolContextUtils;
import com.fishsunny.assistant.utils.ToolExecuteNotifier;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * StandardChatTranslateFactory
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/24 10:50
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StandardChatTranslateFactory {

    private final ObjectMapper objectMapper;
    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final SessionMessageBus sessionMessageBus;
    private final BackgroundToolResponseBus backgroundToolResponseBus;
    private final ToolExecutor toolExecutor;
    private final TTSSettings ttsSettings;

    public ChatHttpHandler.InTranslateCallback createInTranslateCallback(
            ReActDependence reActDependence,
            ReActData reActData
    ) {
        ChatSession chatSession = reActData.chatSession;
        ChatRequest request = reActData.request;
        AssistantSettings assistantSettings = reActDependence.assistantSettings;
        WebSocketSession session = reActDependence.session;
        return new ChatHttpHandler.InTranslateCallback() {
            @Override
            public void onTranslate(AIResponse response) {
                ChatResponse chatResponse = (ChatResponse) response;
                try {
                    chatResponse.setSessionId(chatSession.getId());
                    for (ChatMessage message : chatResponse.getMessages()) {
                        ChatMessage last = ObjectUtils.getLast(request.getMessages());
                        String parentId = last == null ? null : last.getId();
                        message.makeInsertable(chatSession.getId(), parentId, assistantSettings.getAssistantName());
                    }
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatResponse)));
                } catch (Exception e) {
                    log.error("发送消息失败: {}", e.getMessage());
                }
            }

            /**
             * 本轮建连中：connect 之前推送，前端在在途气泡上显示「连接中」。
             * 走 session（总线包装）发送 → 广播给该会话所有连接，重连客户端也能收到。
             */
            @Override
            public void onRequestConnecting() {
                try {
                    session.sendMessage(new TextMessage(ControlSign.SIGN_REQUEST_CONNECTING + chatSession.getId()));
                } catch (Exception e) {
                    log.warn("推送连接中状态信号失败: {}", e.getMessage());
                }
            }

            /**
             * 连接已建立、模型尚未产出第一条内容：前端把「连接中」换成「思考中」。
             * 本回调在收流泵线程上执行，但一定早于首个内容帧，帧序仍由总线锁保证。
             */
            @Override
            public void onRequestThinking() {
                try {
                    session.sendMessage(new TextMessage(ControlSign.SIGN_REQUEST_THINKING + chatSession.getId()));
                } catch (Exception e) {
                    log.warn("推送思考中状态信号失败: {}", e.getMessage());
                }
            }

            /**
             * TTS 逐句音频：包成带 sessionId 的帧走同一 WS（总线），与文本帧同序。
             * 前端拦截 ###TTS_AUDIO### 写入当前 streaming assistant 消息的 extension 并播放；
             * 帧不参与断线重放（shouldReplay 排除）。
             */
            @Override
            public void onTTSAudio(String audioBase64) {
                try {
                    String frame = ControlSign.SIGN_TTS_AUDIO + objectMapper.writeValueAsString(
                            Map.of("sessionId", chatSession.getId(), "audio", audioBase64));
                    session.sendMessage(new TextMessage(frame));
                } catch (Exception e) {
                    log.error("发送 TTS 音频帧失败: {}", e.getMessage());
                }
            }
        };
    }

    public ChatHttpHandler.CompleteCallback createOnCompleteCallback(
            ReActDependence reActDependence,
            ReActOption reActOption,
            ReActData reActData
            ) {
        return  (result, lastResp) -> {
            var toolCalls = result.toolCalls();
            boolean haveToolCall = !CollectionUtils.isEmpty(toolCalls);

            // ReAct依赖
            WebSocketSession session = reActDependence.session;
            AssistantSettings assistantSettings = reActDependence.assistantSettings;

            // ReAct选项
            ChatProvider chatProvider = reActOption.chatProvider;

            // ReAct数据
            ChatSession chatSession = reActData.chatSession;
            ChatRequest request = reActData.request;
            List<ChatMessage> collector = reActData.collector;

            // 落盘w
            try {
                ChatMessage last = ObjectUtils.getLast(request.getMessages());
                String parentId = last == null ? null : last.getId();
                List<ChatToolRequest> toolCallRequests = new ArrayList<>();
                // 如果 AI 调用了工具，将工具调用的参数注入
                if (haveToolCall) {
                    List<ChatToolRequest> convert = ChatToolRequest.convert(toolCalls);
                    toolCallRequests.addAll(convert);
                }
                ChatMessage readyToSaveChatMessage = new ChatMessage()
                        .assistant(result.content(), result.reasoning(), toolCallRequests)
                        .makeInsertable(chatSession.getId(), parentId, assistantSettings.getAssistantName());

                if (chatProvider.getBeforeSaveAssistantProvider() != null) {
                    readyToSaveChatMessage = chatProvider.getBeforeSaveAssistantProvider().apply(readyToSaveChatMessage);
                }

                // TTS 整轮完整音频入库：塞进 assistant 消息 extension（前端播放/历史回放用；
                // 逐句帧只是实时通道，不持久化）
                String fullAudio = result.audioBase64();
                if (StringUtils.hasText(fullAudio)) {
                    readyToSaveChatMessage.getExtension().put("ttsAudio",
                            Map.of("audio", fullAudio, "format", ttsSettings.getFormat()));
                }

                // 推理签名（Anthropic extended thinking / Gemini thought signature）入库：
                // chat_message 没有该列，塞进 extension 这个 JSON 列，会话重载后仍能原样回传给模型。
                // 必须赶在 appendAssistantMessage 落库之前写，且放在 beforeSave 钩子之后（钩子可能换掉对象）
                String reasoningSignature = result.reasoningSignature();
                if (StringUtils.hasText(reasoningSignature)) {
                    readyToSaveChatMessage.getExtension()
                            .put(ChatMessage.EXTENSION_REASONING_SIGNATURE, reasoningSignature);
                }

                // token 用量：本轮各项写进消息 extension（chat_usage），前端据此展示「每轮消耗」
                TokenUsage usage = result.usage();
                boolean hasUsage = usage != null && !usage.isEmpty();
                if (hasUsage) {
                    readyToSaveChatMessage.getExtension().put(ChatUsage.MESSAGE_KEY, usage.toMap());
                }

                ChatMessage assistantMessage = appendAssistantMessage(readyToSaveChatMessage);
                // 消息落库成功后，把本轮用量累计到会话 extension（chat_ 前缀，合并写入），
                // 并把更新后的会话下发给前端，让侧边栏/顶栏的会话累计即时刷新
                if (hasUsage && accumulateSessionUsage(chatSession, usage)) {
                    try {
                        session.sendMessage(new TextMessage(ControlSign.UPDATE_SESSION
                                + objectMapper.writeValueAsString(chatSession)));
                    } catch (Exception e) {
                        log.warn("推送会话 token 累计失败: {}", e.getMessage());
                    }
                }
                // 内存链路同时留在字段上，本轮后续（工具调用循环）直接用
                if (StringUtils.hasText(reasoningSignature)) {
                    assistantMessage.setReasoningSignature(reasoningSignature);
                }
                // 添加助手消息
                ChatResponse response = new ChatResponse().afterAIResponse(assistantMessage);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                request.getMessages().add(assistantMessage);
                collector.add(assistantMessage);
            } catch (Exception e) {
                log.error("保存助手消息失败: {}", e.getMessage());
                throw new RuntimeException("保存助手消息失败: " + e.getMessage());
            }

            // 执行工具
            if (haveToolCall) {
                ChatMessage last = ObjectUtils.getLast(request.getMessages());
                if (last == null) {
                    throw new RuntimeException("Error message data: missed tool call message");
                }

                // 并行执行
                // 构建上下文：session 用总线包装器，工具发送的消息（确认/执行状态等）走总线广播，重连客户端也能收到
                Map<String, Object> context = ToolContextUtils.minimumBuild(session, chatSession, request);
                if (chatProvider.getContextProvider() != null) {
                    context = chatProvider.getContextProvider().apply(context);
                }
                // 附带本轮的完整 messages 快照，供 AI 安全审查等消费方自行提取上下文（如判断用户意图），其它工具无感
                context.put(SecurityService.CTX_MESSAGES, new ArrayList<>(request.getMessages()));
                // 已中止：不真正执行工具，但必须为每个 tool_call 补占位响应并落库 ——
                // assistant 消息里的 tool_calls 若没有对应回话，下一轮请求会被模型直接拒绝
                List<ToolExecutor.ToolExecuteResponse> toolResults = ChatCancelContext.isCancelled()
                        ? ToolExecutor.cancelledResponses(toolCalls)
                        : toolExecutor.executeAdapter(toolCalls, context,
                        ToolExecuteNotifier.buildProvider(session, chatSession.getId(), objectMapper),
                        ToolExecuteNotifier.buildResponseHandleProvider(session, chatSession.getId(), objectMapper));
                // 构建工具消息
                List<ChatMessage> toolMessages = new ArrayList<>();
                for (int i = 0; i < toolCalls.size(); i++) {
                    AIAdapter.ToolCall toolcall = toolCalls.get(i);
                    ToolExecutor.ToolExecuteResponse toolResult = toolResults.get(i);
                    try {
                        toolMessages.add(new ChatMessage()
                                .tool(toolcall.getId(), objectMapper.writeValueAsString(toolResult),
                                        MessageContent.toMessageContents(toolResult.getMultimodalContents()))
                                .makeInsertable(chatSession.getId(), last.getId(), toolcall.getFunction().getName())
                        );
                    } catch (Exception e) {
                        log.error("构建工具消息失败: {}", e.getMessage());
                        throw new RuntimeException("构建工具消息失败: " + e.getMessage());
                    }
                }
                // 保存工具消息
                try {
                    List<ChatMessage> toolResponseMessages = appendToolMessage(toolMessages);
                    request.getMessages().addAll(toolResponseMessages);
                    collector.addAll(toolResponseMessages);

                    // 工具消息已落库：推送 init_tool 携带真实 DB id，前端校准占位/结果消息的假 id
                    // （否则 replace 时父工具消息按真实 parentId 在前端列表中找不到）
                    ChatResponse toolInit = new ChatResponse().afterToolSaved(toolResponseMessages);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(toolInit)));

                    String roundEnd = ControlSign.SIGN_TOOL_CALL_FINISH + chatSession.getId();;

                    session.sendMessage(new TextMessage(roundEnd));
                } catch (Exception e) {
                    log.error("保存工具消息失败: {}", e.getMessage());
                    throw new RuntimeException("保存工具消息失败: " + e.getMessage());
                }
            }
        };
    }

    public record ReActDependence(AssistantSettings assistantSettings, WebSocketSession session) {}
    public record ReActOption(ChatProvider chatProvider) {}
    public record ReActData(List<ChatMessage> collector, ChatSession chatSession, ChatRequest request) {}

    private ChatMessage appendAssistantMessage(ChatMessage chatMessage) throws Exception {
        try {
            // 落库前探一次后台任务总线：在途结果作为追加文本块挂到这条助手消息末尾
            backgroundToolResponseBus.attachPending(chatMessage);
            ChatMessage saved = chatMessageService.save(chatMessage);
            // 助手消息落库后清空总线缓冲：已落库内容由历史兜底，缓冲只需保留此后产生的在途事件，
            // 使重连 replay 从最近一条落库消息开始，而不是从上一轮 user 重放整轮。
            // 注意：只在落助手时清空——工具是并发执行的，落工具时清空会误删其它工具尚未消费完的事件。
            sessionMessageBus.clearBuffer(saved.getSessionId());
            return saved;
        } catch (UserException e) {
            throw new UserException("保存助手消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存助手消息失败，系统发生了未知错误: " + e.getMessage());
        }
    }

    private List<ChatMessage> appendToolMessage(List<ChatMessage> messages) throws Exception {
        List<ChatMessage> resultMessages = new ArrayList<>();
        try {
            // 落库前探一次总线：挂到最后一条工具消息末尾
            // （工具链执行完时已消费过一轮，这里兜的是「工具跑完、落库前」才到的那批）
            if (!messages.isEmpty()) {
                backgroundToolResponseBus.attachPending(messages.getLast());
            }
            for (ChatMessage message : messages) {
                chatMessageService.save(message);
                resultMessages.add(message);
            }
            return resultMessages;
        } catch (UserException e) {
            throw new UserException("保存工具结果消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存工具结果消息失败，系统发生了未知错误: " + e.getMessage());
        }
    }

    private boolean accumulateSessionUsage(ChatSession chatSession, TokenUsage usage) {
        try {
            Map<String, Object> extension = chatSession.ensureExtension();
            Map<String, Object> fields = new LinkedHashMap<>();
            addLong(extension, fields, ChatUsage.SESSION_PROMPT_TOKENS, usage.getPromptTokens());
            addLong(extension, fields, ChatUsage.SESSION_COMPLETION_TOKENS, usage.getCompletionTokens());
            addLong(extension, fields, ChatUsage.SESSION_TOTAL_TOKENS, usage.getTotalTokens());
            addLong(extension, fields, ChatUsage.SESSION_CACHED_TOKENS, usage.getCachedTokens());
            addLong(extension, fields, ChatUsage.SESSION_REASONING_TOKENS, usage.getReasoningTokens());
            addLong(extension, fields, ChatUsage.SESSION_ROUNDS, 1);
            chatSession.setExtension(chatSessionService.mergeExtension(chatSession.getId(), fields));
            return true;
        } catch (Exception e) {
            log.warn("累计会话 token 用量失败: {}", e.getMessage());
            return false;
        }
    }

    /** 累加一个 long 计数：同时写入内存 extension 与待合并 fields（缺失或非数字按 0 起算） */
    private void addLong(Map<String, Object> extension, Map<String, Object> fields, String key, Integer delta) {
        if (delta == null) {
            return;
        }
        long base = 0L;
        Object current = extension.get(key);
        if (current instanceof Number number) {
            base = number.longValue();
        }
        long value = base + delta;
        extension.put(key, value);
        fields.put(key, value);
    }

}
