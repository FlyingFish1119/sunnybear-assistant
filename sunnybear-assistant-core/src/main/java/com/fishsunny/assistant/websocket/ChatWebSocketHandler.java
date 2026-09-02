package com.fishsunny.assistant.websocket;

/*
 * @Usage WebSocket 对话处理器 —— 薄层编排器，负责解析、校验、分发
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Primary
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    protected final ServiceProcessor serviceProcessor;
    protected final TempChatProcessor tempChatProcessor;
    protected final ChatProcessor chatProcessor;
    protected final TaskExecutor chatAsyncExecutor;
    protected final ObjectMapper objectMapper;

    /**
     * 记录每个连接的活跃异步任务数
     */
    protected final Map<String, Integer> activeTaskCount = new ConcurrentHashMap<>();

    /**
     * 会话消息总线：消息按 chatSessionId 发布并广播给订阅连接，重连连接可订阅续传
     */
    protected final SessionMessageBus sessionMessageBus;

    public ChatWebSocketHandler(ServiceProcessor serviceProcessor,
                                TempChatProcessor tempChatProcessor,
                                ChatProcessor chatProcessor,
                                TaskExecutor chatAsyncExecutor,
                                ObjectMapper objectMapper,
                                SessionMessageBus sessionMessageBus) {
        this.serviceProcessor = serviceProcessor;
        this.tempChatProcessor = tempChatProcessor;
        this.chatProcessor = chatProcessor;
        this.chatAsyncExecutor = chatAsyncExecutor;
        this.objectMapper = objectMapper;
        this.sessionMessageBus = sessionMessageBus;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接已建立: {}", session.getId());
        activeTaskCount.put(session.getId(), 0);
    }

    /**
     * 公共桥接方法，供 WebSocket 客户端复用完整对话流程。
     */
    public void processMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        handleTextMessage(session, message);
    }

    /**
     * 获取 ChatToAiProvider，默认为 null、通过子类继承使用
     */
    public ChatProvider chatToAiProvider() {
        return ChatProvider.DEFAULT;
    }

    /**
     * 额外的信号解析 Hook。子类可重写此方法以拦截并处理特定信号。
     * 在正常的聊天流程之前调用。
     *
     * @param session WebSocket 会话
     * @param payload 原始消息文本
     * @return true = 信号已被处理，跳过正常聊天流程；false = 继续正常聊天流程
     */
    protected boolean handleAdditionalSignal(WebSocketSession session, String payload) {
        return false;
    }

    protected final boolean replayMessage(String payload, SynchronizedWebSocketSession safeSession) throws Exception {
        if (payload.startsWith(ControlSign.SIGN_REQUIRE_REPLAY_MESSAGE)) {
            String sessionId = payload.substring(ControlSign.SIGN_REQUIRE_REPLAY_MESSAGE.length());
            // 独占订阅会话总线：返回当前进行中一轮的缓存快照，之后的新消息实时广播到此连接
            // 订阅身份统一用 delegate()（原始连接），与 handleChatSession 的订阅一致，
            // 避免包装器与原始连接同时出现在订阅集合导致同一条消息被推送两次
            List<SessionMessageBus.Event> replayEvents = sessionMessageBus.subscribeExclusive(sessionId, safeSession.delegate());
            if (CollectionUtils.isEmpty(replayEvents)) {
                return true;
            }
            log.info("会话 [{}] 订阅总线，回放 {} 条消息: {}", safeSession.getId(), replayEvents.size(), sessionId);
            // 回放期间持有连接锁：与总线广播互斥，保证快照完整送达后再接收直播，chunk 不交错乱序
            synchronized (safeSession.delegate()) {
                safeSession.sendMessage(new TextMessage(ControlSign.SIGN_REPLAY_MESSAGE + sessionId));
                for (SessionMessageBus.Event event : replayEvents) {
                    safeSession.sendMessage(new TextMessage(event.payload()));
                }
            }
            return true;
        }
        return false;
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        chatAsyncExecutor.execute(() -> {
            // 用线程安全的包装器保护 session，防止多线程并发 sendMessage 时出现 TEXT_PARTIAL_WRITING
            final SynchronizedWebSocketSession safeSession = new SynchronizedWebSocketSession(session);

            // 提升到 try 外：错误处理时需要回传 sessionId，让前端能按会话清理流式状态
            ChatMessageRequest request = null;
            ChatSession chatSession = null;
            try {
                String payload = message.getPayload();

                if (replayMessage(payload, safeSession)) {
                    return;
                }

                // 让子类有机会拦截并处理特定信号（如战斗回合行动）
                if (handleAdditionalSignal(safeSession, payload)) {
                    return;
                }

                // 检查请求
                request = new ChatMessageRequest().parseAndValidate(payload, objectMapper);

                if (request.isTemp()) {
                    tempChatProcessor.chat(request, safeSession);
                    return;
                }

                // 处理会话
                boolean enableSwitchPro = true;
                if (chatToAiProvider().getEnableSwitchPro() != null) {
                    enableSwitchPro = Boolean.TRUE.equals(chatToAiProvider().getEnableSwitchPro().get());
                }
                ServiceProcessor.ChatSessionModeParseResult parseResult = serviceProcessor.handleChatSession(request, safeSession, enableSwitchPro);

                // 处理请求
                chatSession = parseResult.chatSession();

                if (chatSession == null) {
                    throw new UserException("无效的会话 ID");
                }

                try {
                    activeTaskCount.merge(safeSession.getId(), 1, Integer::sum);

                    sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_START + chatSession.getId());

                    WebSocketSession busSession = sessionMessageBus.wrap(safeSession, chatSession.getId());

                    List<ChatMessage> chatMessages = chatProcessor.chatToAi(parseResult.messages(), chatSession, busSession, chatToAiProvider());

                    // 如果是新的会话，则生成标题
                    if (parseResult.isNewChat()) {
                        serviceProcessor.generateTitle(chatSession, request.getContent(), chatMessages.getFirst().resolveText());
                    }

                    sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_END + chatSession.getId());
                } catch (IOException e) {
                    log.warn("WebSocket 发送失败，连接可能已关闭 [{}]: {}", safeSession.getId(), e.getMessage());
                } catch (Exception e) {
                    log.error("chatToAi async error [{}]: {}", safeSession.getId(), e.getMessage(), e);
                    sendErrorToFrontend(safeSession, chatSession.getId(), "AI 对话异常: " + e.getMessage());
                } finally {
                    activeTaskCount.merge(safeSession.getId(), -1, Integer::sum);
                }

            } catch (UserException e) {
                log.warn(e.getMessage());
                sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, e.getMessage());
            } catch (Exception e) {
                log.error("error: {}", e.getMessage(), e);
                sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, "系统内部错误，请稍后重试");
            }
        });
    }


    /** 将错误信息通过 ChatResponse 推送到前端 */
    protected void sendErrorToFrontend(WebSocketSession safeSession, String sessionId, String errorMessage) {
        try {
            ChatResponse errorResp = new ChatResponse().afterError(sessionId != null ? sessionId : "", errorMessage);
            safeSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResp)));
        } catch (Exception ignored) {
            log.warn("发送错误信息到前端失败，连接可能已断开");
        }
        // 本轮异常中止：清空总线上的当前轮缓存，避免残留半截事件影响后续订阅
        if (sessionId != null) {
            sessionMessageBus.reset(sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        log.info("WebSocket 连接已关闭: {}, 状态: {}, 未完成任务数: {}",
                session.getId(), status, activeTaskCount.getOrDefault(session.getId(), 0));
        activeTaskCount.remove(session.getId());
        sessionMessageBus.unsubscribeAll(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 传输错误 [{}]: {}", session.getId(), exception.getMessage());
        activeTaskCount.remove(session.getId());
        sessionMessageBus.unsubscribeAll(session);
        super.handleTransportError(session, exception);
    }
}
