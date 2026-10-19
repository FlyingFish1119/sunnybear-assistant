package com.fishsunny.assistant.plug.qq.service;

/*
 * @Usage QQ → Web 对话代理 —— 调用 ChatWebSocketHandler.processMessage 复用完整对话流程。
 *        流式接管：CapturingSession 累积流式增量，命中分条记号即发一片，轮次收尾时发出残余，
 *        使 QQ 侧能边生成边收到消息，而不是等整轮 ReAct 结束才一次性吐出。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.cancel.ChatCancelRegistry;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.plug.qq.component.QQChatWebSocketHandler;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 CapturingSession 把 QQ 消息注入 WebSocket 对话管道，
 * 使得 QQ 对话在网页端可以像普通会话一样显示和继续操作。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ChatWebSocketProxy {

    /**
     * 分条边界：段与段之间的空行。AI 用空行分段，段落之间就作为独立的 QQ 消息发出，
     * 不引入人造记号；段内的普通换行只作排版，不触发分条。
     */
    private static final Pattern SEGMENT_BOUNDARY = Pattern.compile("\\n[ \\t\\r]*\\n");

    private final QQChatWebSocketHandler handler;
    private final ObjectMapper objectMapper;
    private final ChatCancelRegistry cancelRegistry;
    private final SessionMessageBus sessionMessageBus;

    /** QQ 用户标识 → 网页端 ChatSession ID */
    private final Map<String, String> sessionMap = new ConcurrentHashMap<>();

    /**
     * 将 QQ 消息注入 Web 对话管道，所有 AI 回复通过 callback 推送。
     *
     * @param qqUserId         QQ 用户标识（QQ 号字符串）
     * @param userName         显示的用户名
     * @param message          用户消息文本
     * @param progressCallback AI 回复 / 进度回调，不可为 null；流式下会按分片多次调用
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
        try {
            if (!finished) {
                log.warn("QQ 对话超时，session={}", session.id);
                progressCallback.accept("处理超时，请稍后重试");
            }
        } finally {
            releaseSession(qqUserId, session);
        }
    }

    /**
     * 把这一轮的伪装会话从总线摘除，并记下会话映射供下一轮 APPEND 复用。
     * <p>CapturingSession 是伪造连接，没有「连接关闭」这个事件（前端真实连接靠 afterConnectionClosed 退订），
     * 必须在这里显式摘除：否则订阅者会随对话轮数一轮一轮累积，同一帧被广播给历次残留会话，
     * 每个残留会话又各自握着自己那一轮回调往 QQ 发一遍，表现就是第 N 轮回复被重复发送 N 次。
     */
    private void releaseSession(String qqUserId, CapturingSession session) {
        String sessionId = session.capturedSessionId;
        if (sessionId == null) {
            // 会话都没建起来（如请求校验失败）：订阅未发生，无需摘除
            return;
        }
        sessionMap.put(qqUserId, sessionId);
        sessionMessageBus.unsubscribe(sessionId, session);
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
            // 与 ChatController.stopStreaming 统一走会话级取消注册表：一次停止覆盖该会话所有在途轮次
            cancelRegistry.cancel(sessionId);
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

        /**
         * 流式增量缓冲：正文先落在这里，遇到段与段之间的空行才把上一段交出去。
         * 末尾那个孤零零的换行继续留着——空行完全可能被拆在两个 chunk 里
         * （前一片结尾一个换行、后一片开头一个换行），当场判断会把段落切错。
         */
        private final StringBuilder streamBuffer = new StringBuilder();

        /** 本轮助手消息是否已有流式增量流入：有则 init_assistant 只做收尾，不重复发一遍正文 */
        private boolean streamedThisTurn;

        CapturingSession(String id, Consumer<String> progressCallback, ObjectMapper objectMapper) {
            this.id = id;
            this.progressCallback = progressCallback;
            this.objectMapper = objectMapper;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            String payload = message.getPayload() instanceof String ? (String) message.getPayload() : null;
            if (payload == null) {
                return;
            }

            if (payload.startsWith(ControlSign.SIGN_START)) {
                capturedSessionId = payload.substring(ControlSign.SIGN_START.length());
                return;
            }
            if (payload.startsWith(ControlSign.SIGN_END)) {
                List<String> tail;
                synchronized (streamBuffer) {
                    tail = flushSegments();
                    streamedThisTurn = false;
                }
                tail.forEach(this::emit);
                latch.countDown();
                return;
            }

            try {
                handleResponse(objectMapper.readValue(payload, ChatResponse.class));
            } catch (Exception e) {
                // 控制信号（TOOL_ASK / QUESTION 等）与其它非对话帧：不入 QQ，忽略
                log.debug("QQ 会话收到非对话帧，已忽略: {}", e.getMessage());
            }
        }

        private void handleResponse(ChatResponse resp) {
            String status = resp.getStatus();
            // done 帧在部分协议（如 Gemini）里同样携带正文增量，与 chunk 一并按增量处理
            if (ChatResponse.STATUS_CHUNK.equals(status) || ChatResponse.STATUS_DONE.equals(status)) {
                appendDelta(resp);
            } else if (ChatResponse.STATUS_INIT_ASSISTANT.equals(status)) {
                finishAssistant(resp);
            } else if (ChatResponse.STATUS_ERROR.equals(status)) {
                failTurn(resp);
            }
            // 其余状态（工具消息、临时提示等）都属于过程信息，不外显到 QQ
        }

        /**
         * 流式增量：原样追加（纯空白增量也要保留，否则流式下 \n\n 这类段落分隔会丢），
         * 攒到空行就把上一段作为一条发出去。
         */
        private void appendDelta(ChatResponse resp) {
            String delta = textOf(resp);
            if (delta == null || delta.isEmpty()) {
                return;
            }
            List<String> ready;
            synchronized (streamBuffer) {
                streamBuffer.append(delta);
                streamedThisTurn = true;
                ready = drainSegments();
            }
            ready.forEach(this::emit);
        }

        /**
         * 助手消息完成：流式已接管则只发残余；若本轮根本没收到增量（非流式协议），
         * 就把这条完整消息按段落切分后补发，保证两条路径行为一致。
         */
        private void finishAssistant(ChatResponse resp) {
            List<String> ready;
            synchronized (streamBuffer) {
                if (!streamedThisTurn) {
                    String whole = textOf(resp);
                    if (whole != null) {
                        streamBuffer.append(whole);
                    }
                }
                ready = drainSegments();
                ready.addAll(flushSegments());
                streamedThisTurn = false;
            }
            ready.forEach(this::emit);
        }

        /**
         * 本轮出错：先发出已经生成的内容，再把错误原因说明出去，并结束等待。
         * 不加这一步，错误路径不会推 END 帧，QQ 侧只能干等到 300 秒超时才吐一句「处理超时」。
         */
        private void failTurn(ChatResponse resp) {
            List<String> ready;
            synchronized (streamBuffer) {
                ready = flushSegments();
                streamedThisTurn = false;
            }
            ready.forEach(this::emit);
            String reason = textOf(resp);
            if (reason != null) {
                emit(reason);
            }
            latch.countDown();
        }

        /** 锁内：切出缓冲区里所有已成形的段落（作为分隔符的空行不进入正文） */
        private List<String> drainSegments() {
            List<String> segments = new ArrayList<>();
            while (true) {
                Matcher matcher = SEGMENT_BOUNDARY.matcher(streamBuffer);
                if (!matcher.find()) {
                    return segments;
                }
                String segment = streamBuffer.substring(0, matcher.start()).strip();
                streamBuffer.delete(0, matcher.end());
                if (!segment.isEmpty()) {
                    segments.add(segment);
                }
            }
        }

        /** 锁内：把缓冲区残余整体取走并清空 */
        private List<String> flushSegments() {
            String tail = streamBuffer.toString().strip();
            streamBuffer.setLength(0);
            return tail.isEmpty() ? List.of() : List.of(tail);
        }

        /** 取一帧里的正文文本；工具消息与思考内容（reasoningContent）都不计入 */
        private String textOf(ChatResponse resp) {
            if (CollectionUtils.isEmpty(resp.getMessages())) {
                return null;
            }
            StringBuilder text = new StringBuilder();
            for (ChatMessage msg : resp.getMessages()) {
                if (ChatMessage.ROLE_TOOL.equals(msg.getRole())) {
                    continue;
                }
                String content = msg.resolveText();
                if (content != null) {
                    text.append(content);
                }
            }
            return text.isEmpty() ? null : text.toString();
        }

        private void emit(String text) {
            if (progressCallback != null && !text.isEmpty()) {
                progressCallback.accept(text);
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
