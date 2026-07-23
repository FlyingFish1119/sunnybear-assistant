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
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import lombok.NonNull;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;


@Primary
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);

    private final ServiceProcessor serviceProcessor;
    private final ChatProcessor chatProcessor;
    private final TaskExecutor chatAsyncExecutor;
    private final ObjectMapper objectMapper;

    /**
     * 记录每个连接的活跃异步任务数
     */
    private final Map<String, Integer> activeTaskCount = new ConcurrentHashMap<>();

    public ChatWebSocketHandler(ServiceProcessor serviceProcessor,
                                ChatProcessor chatProcessor,
                                TaskExecutor chatAsyncExecutor,
                                ObjectMapper objectMapper) {
        this.serviceProcessor = serviceProcessor;
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
        return null;
    }

    /**
     * 是否启用 Pro 模型自动切换。子类可重写以关闭此功能。
     */
    protected boolean isProModelEnabled() {
        return true;
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

            try {
                String payload = message.getPayload();

                // 让子类有机会拦截并处理特定信号（如战斗回合行动）
                if (handleAdditionalSignal(safeSession, payload)) {
                    return;
                }

                // 检查请求
                ChatMessageRequest request;
                request = serviceProcessor.checkRequest(payload);

                // 处理请求
                boolean isNewChat;
                List<ChatMessage> messages = new ArrayList<>();
                ChatSession chatSession = null;
                switch (request.getMode()) {
                    case ChatMessageRequest.MODE_CREATE:
                        isNewChat = true;
                        // 先判断是否需要高级模型（角色对话跳过），再创建 session
                        boolean enablePro = isProModelEnabled() && serviceProcessor.judgeProModel(request.getContent());
                        chatSession = serviceProcessor.createChatSession(enablePro);
                        request.setSessionId(chatSession.getId());
                        // 先写文件，再创建带文件引用的用户消息
                        List<String> createFileUrls = serviceProcessor.writeSessionFile(request.getFiles(), chatSession);
                        messages.add(serviceProcessor.appendUserMessage(
                                chatSession.getId(), null, request.getContent(), createFileUrls, safeSession));
                        break;
                    case ChatMessageRequest.MODE_APPEND:
                        isNewChat = false;
                        chatSession = serviceProcessor.findChatSession(request.getSessionId());
                        messages = serviceProcessor.findHistoryMessages(request.getSessionId());
                        ChatMessage last = ObjectUtils.getLast(messages);
                        String parentId = last == null ? null : last.getId();
                        List<String> appendFileUrls = serviceProcessor.writeSessionFile(request.getFiles(), chatSession);
                        messages.add(serviceProcessor.appendUserMessage(
                                request.getSessionId(), parentId, request.getContent(), appendFileUrls, safeSession));
                        break;
                    case ChatMessageRequest.MODE_REPLACE:
                        isNewChat = false;
                        chatSession = serviceProcessor.findChatSession(request.getSessionId());
                        messages = serviceProcessor.handleReplace(request, safeSession);
                        break;
                    case ChatMessageRequest.MODE_EDIT:
                        isNewChat = false;
                        chatSession = serviceProcessor.findChatSession(request.getSessionId());
                        messages = serviceProcessor.handleEdit(request, safeSession);
                        break;
                    default:
                        throw new UserException("无效的请求模式");
                }
                if (chatSession == null) {
                    throw new UserException("无效的会话 ID");
                }

                try {
                    activeTaskCount.merge(safeSession.getId(), 1, Integer::sum);

                    safeSession.sendMessage(new TextMessage(ControlSign.SIGN_START + chatSession.getId()));

                    List<ChatMessage> chatMessages = chatProcessor.chatToAi(messages, chatSession, safeSession, chatToAiProvider());

                    // 如果是新的会话，则生成标题
                    if (isNewChat) {
                        serviceProcessor.generateTitle(session, chatSession, request.getContent(), chatMessages.get(0).resolveText());
                    }

                    safeSession.sendMessage(new TextMessage(ControlSign.SIGN_END + chatSession.getId()));
                } catch (IOException e) {
                    log.warn("WebSocket 发送失败，连接可能已关闭 [{}]: {}", session.getId(), e.getMessage());
                } catch (Exception e) {
                    log.error("chatToAi async error [{}]: {}", session.getId(), e.getMessage(), e);
                    sendErrorToFrontend(safeSession, chatSession.getId(), "AI 对话异常: " + e.getMessage());
                } finally {
                    activeTaskCount.merge(safeSession.getId(), -1, Integer::sum);
                }

            } catch (UserException e) {
                log.warn(e.getMessage());
                sendErrorToFrontend(safeSession, null, e.getMessage());
            } catch (Exception e) {
                log.error("error: {},\n stacktrace: {}", e.getMessage(), e.getStackTrace());
                sendErrorToFrontend(safeSession, null, "系统内部错误，请稍后重试");
            }

        });
    }

    /** 将错误信息通过 ChatResponse 推送到前端 */
    private void sendErrorToFrontend(WebSocketSession safeSession, String sessionId, String errorMessage) {
        try {
            ChatResponse errorResp = new ChatResponse()
                    .setStatus(ChatResponse.STATUS_ERROR)
                    .setSessionId(sessionId != null ? sessionId : "")
                    .setMessages(List.of(new ChatMessage().assistant(errorMessage, null, null)));
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
