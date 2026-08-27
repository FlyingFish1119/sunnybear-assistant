package com.fishsunny.assistant.websocket;

/*
 * @Usage WebSocket 对话处理器 —— 薄层编排器，负责解析、校验、分发
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    public ChatWebSocketHandler(ServiceProcessor serviceProcessor,
                                TempChatProcessor tempChatProcessor,
                                ChatProcessor chatProcessor,
                                TaskExecutor chatAsyncExecutor,
                                ObjectMapper objectMapper) {
        this.serviceProcessor = serviceProcessor;
        this.tempChatProcessor = tempChatProcessor;
        this.chatProcessor = chatProcessor;
        this.chatAsyncExecutor = chatAsyncExecutor;
        this.objectMapper = objectMapper;
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

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        chatAsyncExecutor.execute(() -> {
            // 用线程安全的包装器保护 session，防止多线程并发 sendMessage 时出现 TEXT_PARTIAL_WRITING
            final WebSocketSession safeSession = new SynchronizedWebSocketSession(session);

            // 提升到 try 外：错误处理时需要回传 sessionId，让前端能按会话清理流式状态
            ChatMessageRequest request = null;
            try {
                String payload = message.getPayload();

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
                boolean enableSwitchPro = Boolean.TRUE.equals(chatToAiProvider().getEnableSwitchPro().get());
                ServiceProcessor.ChatSessionModeParseResult parseResult = serviceProcessor.handleChatSession(request, safeSession, enableSwitchPro);

                // 处理请求
                ChatSession chatSession = parseResult.chatSession();

                if (chatSession == null) {
                    throw new UserException("无效的会话 ID");
                }

                try {
                    activeTaskCount.merge(safeSession.getId(), 1, Integer::sum);

                    safeSession.sendMessage(new TextMessage(ControlSign.SIGN_START + chatSession.getId()));

                    List<ChatMessage> chatMessages = chatProcessor.chatToAi(parseResult.messages(), chatSession, safeSession, chatToAiProvider());

                    // 如果是新的会话，则生成标题
                    if (parseResult.isNewChat()) {
                        serviceProcessor.generateTitle(safeSession, chatSession, request.getContent(), chatMessages.get(0).resolveText());
                    }

                    safeSession.sendMessage(new TextMessage(ControlSign.SIGN_END + chatSession.getId()));
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
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        log.info("WebSocket 连接已关闭: {}, 状态: {}, 未完成任务数: {}",
                session.getId(), status, activeTaskCount.getOrDefault(session.getId(), 0));
        activeTaskCount.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 传输错误 [{}]: {}", session.getId(), exception.getMessage());
        activeTaskCount.remove(session.getId());
        super.handleTransportError(session, exception);
    }
}
