package com.fishsunny.assistant.plug.comfyui.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * ComfyUI 桥接服务，管理来自 ComfyUI Agent JAR 的 WebSocket 连接。
 * <p>
 * 提供 RPC 调用模式：服务端工具调用 {@link #sendCommand(String, String)}
 * → 发送 JSON 命令到 Agent → 阻塞等待返回结果 → 返回给工具。
 */
@Component
public class ComfyUIBridgeService extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ComfyUIBridgeService.class);

    private final ObjectMapper objectMapper;
    /** 当前连接 */
    private volatile WebSocketSession session;
    private volatile String deviceName;
    /** requestId → 等待响应的 CompletableFuture */
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    /** 命令超时（秒），默认 1800（30 分钟），可在 application.yml 中配置 */
    @Value("${plug.comfyui.bridge.command-timeout-s:1800}")
    private int commandTimeout;

    public ComfyUIBridgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== RPC ====================

    public String sendCommand(String method, String paramsJson)
            throws ToolExecutor.ToolExecuteException {
        WebSocketSession s = this.session;
        if (s == null || !s.isOpen()) {
            throw new ToolExecutor.ToolExecuteException("ComfyUI 设备不在线");
        }

        String requestId = UUID.randomUUID().toString();
        String commandJson = String.format(
                "{\"id\":\"%s\",\"method\":\"%s\",\"params\":%s}",
                requestId, method, paramsJson != null ? paramsJson : "{}");

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            synchronized (s) {
                s.sendMessage(new TextMessage(commandJson));
            }
            return future.get(commandTimeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 超时（" + commandTimeout + "s）");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 发送失败: " + e.getMessage());
        }
    }

    // ==================== WebSocket 事件 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("ComfyUI Agent 连接中: {} (session={})", session.getRemoteAddress(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);

            String type = (String) msg.get("type");

            if ("register".equals(type)) {
                String name = (String) msg.get("deviceName");
                // 替换旧连接
                WebSocketSession old = this.session;
                if (old != null && old.isOpen()) {
                    try { old.close(); } catch (IOException ignored) {}
                }
                this.session = session;
                this.deviceName = name;
                log.info("ComfyUI Agent 已注册: {}", name);
                return;
            }

            if ("heartbeat".equals(type)) {
                return;
            }

            String id = (String) msg.get("id");
            if (id != null) {
                CompletableFuture<String> future = pendingRequests.remove(id);
                if (future != null) {
                    String result = (String) msg.get("result");
                    String error = (String) msg.get("error");
                    if (error != null) {
                        future.complete("{\"error\":\"" + error + "\"}");
                    } else {
                        future.complete(result != null ? result : "{}");
                    }
                }
            }
        } catch (Exception e) {
            log.error("解析 Agent 消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        if (this.session != null && this.session.getId().equals(session.getId())) {
            this.session = null;
            this.deviceName = null;
        }
        log.info("ComfyUI Agent 已断开: status={}", status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Agent 连接传输错误 [{}]: {}", session.getId(), exception.getMessage());
        if (this.session != null && this.session.getId().equals(session.getId())) {
            this.session = null;
        }
    }
}
