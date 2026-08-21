package com.fishsunny.assistant.plug.qq.service;

/*
 * @Usage QQ → Web 对话代理 —— 调用 ChatWebSocketHandler.processMessage 复用完整对话流程
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 通过 CapturingSession 把 QQ 消息注入 WebSocket 对话管道，
 * 使得 QQ 对话在网页端可以像普通会话一样显示和继续操作。
 */
@Component
public class ChatWebSocketProxy {

    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketProxy.class);

    private final ChatWebSocketHandler handler;
    private final ObjectMapper objectMapper;

    /** QQ 用户标识 → 网页端 ChatSession ID */
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();

    public ChatWebSocketProxy(ChatWebSocketHandler handler, ObjectMapper objectMapper) {
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    /**
     * 将 QQ 消息注入 Web 对话管道，所有 AI 回复通过 callback 推送。
     *
     * @param qqUserId         QQ 用户标识（QQ 号字符串）
     * @param userName         显示的用户名
     * @param message          用户消息文本
     * @param progressCallback AI 回复 / 进度回调，不可为 null
     */
    public void processMessage(String qqUserId, String userName, String message,
                                Consumer<String> progressCallback) throws Exception {
        CapturingSession session = new CapturingSession("qq-" + qqUserId, progressCallback, objectMapper);

        String existingSessionId = sessionMap.get(qqUserId);
        ChatMessageRequest request = new ChatMessageRequest();
        if (existingSessionId != null) {
            request.setMode(ChatMessageRequest.MODE_APPEND)
                    .setSessionId(existingSessionId)
                    .setContent(message);
        } else {
            request.setMode(ChatMessageRequest.MODE_CREATE)
                    .setContent(message);
        }

        String payload = objectMapper.writeValueAsString(request);
        handler.processMessage(session, new TextMessage(payload));

        boolean finished = session.latch.await(300, TimeUnit.SECONDS);
        if (!finished) {
            log.warn("QQ 对话超时，session={}", session.id);
            progressCallback.accept("处理超时，请稍后重试");
            return;
        }

        if (session.capturedSessionId != null) {
            sessionMap.put(qqUserId, session.capturedSessionId);
        }
    }

    /** 清除指定用户的会话映射 */
    public void clearSession(String qqUserId) {
        stop(qqUserId);
        sessionMap.remove(qqUserId);
    }

    /** 停止当前正在进行的 AI 生成 */
    public void stop(String qqUserId) {
        String sessionId = sessionMap.get(qqUserId);
        if (sessionId != null) {
            ChatHttpHandler.getAllowedContinue().remove(sessionId);
        }
    }

    // ==================== Capturing Session ====================

    private static class CapturingSession implements WebSocketSession {

        private final String id;
        private final CountDownLatch latch = new CountDownLatch(1);
        private final Map<String, Object> attrs = new HashMap<>();
        private final Consumer<String> progressCallback;
        private final ObjectMapper objectMapper;

        String capturedSessionId;

        CapturingSession(String id, Consumer<String> progressCallback, ObjectMapper objectMapper) {
            this.id = id;
            this.progressCallback = progressCallback;
            this.objectMapper = objectMapper;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            String payload = message.getPayload() instanceof String ? (String) message.getPayload() : null;
            if (payload == null) return;

            if (payload.startsWith(ControlSign.SIGN_START)) {
                capturedSessionId = payload.substring(ControlSign.SIGN_START.length());
                return;
            }
            if (payload.startsWith(ControlSign.SIGN_END)) {
                latch.countDown();
                return;
            }

            try {
                ChatResponse resp = objectMapper.readValue(payload, ChatResponse.class);
                String status = resp.getStatus();
                if (CollectionUtils.isEmpty(resp.getMessages()) || progressCallback == null) {
                    return;
                }
                // 只推送完整的消息，跳过流式 chunk
                if (!ChatResponse.STATUS_INIT_ASSISTANT.equals(status) && !ChatResponse.STATUS_TOOL_RESPONSE.equals(status)) {
                    return;
                }
                for (ChatMessage msg : resp.getMessages()) {
                    String text = msg.resolveText();
                    if (!StringUtils.hasText(text)) {
                        continue;
                    }

                    if (ChatMessage.ROLE_TOOL.equals(msg.getRole())) {
                        progressCallback.accept("[工具结果] " + (text.length() > 1000 ? text.substring(0, 1000) + "..." : text));
                    } else {
                        progressCallback.accept(text);
                    }
                }
            } catch (Exception ignored) {
                // 非 ChatResponse 格式（TOOL_ASK 等）跳过
            }
        }

        @Override public String getId() { return id; }
        @Override public boolean isOpen() { return true; }
        @Override public URI getUri() { return null; }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return attrs; }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int limit) {}
        @Override public int getTextMessageSizeLimit() { return 0; }
        @Override public void setBinaryMessageSizeLimit(int limit) {}
        @Override public int getBinaryMessageSizeLimit() { return 0; }
        @Override public List<WebSocketExtension> getExtensions() { return Collections.emptyList(); }
        @Override public void close() {}
        @Override public void close(CloseStatus status) {}
    }
}
