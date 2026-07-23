package com.fishsunny.assistant.plug.android.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 * Android 设备桥接服务，管理来自手机 APK 的 WebSocket 连接池。
 * <p>
 * 提供类似 RPC 的调用模式：服务端工具调用 {@link #sendCommand(String, String, String)}
 * → 发送 JSON 命令到指定设备 → 阻塞等待 APK 返回结果 → 返回给工具。
 * <p>
 * 注册为 Spring WebSocket Handler，监听 {@code /android-bridge} 端点。
 */
@Component
public class AndroidBridgeService extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AndroidBridgeService.class);

    private final ObjectMapper objectMapper;
    /** deviceId → WebSocket 会话 */
    private final Map<String, WebSocketSession> devices = new ConcurrentHashMap<>();
    /** requestId → 等待响应的 CompletableFuture */
    private final Map<String, CompletableFuture<String>> pendingRequests = new ConcurrentHashMap<>();

    /** 命令超时（秒） */
    private static final int COMMAND_TIMEOUT = 30;

    public AndroidBridgeService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    // ==================== 设备管理 ====================

    /** 获取所有已连接设备 */
    public Map<String, WebSocketSession> getDevices() {
        return Map.copyOf(devices);
    }

    /** 获取第一个可用设备 ID（单设备场景的便捷方法） */
    public String getFirstDeviceId() {
        return devices.keySet().stream().findFirst().orElse(null);
    }

    /** 检查设备是否在线 */
    public boolean isOnline(String deviceId) {
        return devices.containsKey(deviceId);
    }

    // ==================== RPC：发送命令并等待响应 ====================

    /**
     * 向指定设备发送命令并等待结果。
     *
     * @param deviceId 设备标识
     * @param method   方法名（click, swipe, type, get_ui_tree, screenshot, launch_app, press_key, scroll, wait_for_text）
     * @param paramsJson 参数 JSON 字符串（如 {@code {"x":500,"y":800}}）
     * @return APK 返回的结果字符串
     * @throws ToolExecutor.ToolExecuteException 设备离线或超时
     */
    public String sendCommand(String deviceId, String method, String paramsJson)
            throws ToolExecutor.ToolExecuteException {
        WebSocketSession session = devices.get(deviceId);
        if (session == null || !session.isOpen()) {
            devices.remove(deviceId);
            throw new ToolExecutor.ToolExecuteException(
                    "设备 [" + deviceId + "] 不在线。可用设备: " + devices.keySet());
        }

        String requestId = UUID.randomUUID().toString();
        String commandJson = String.format(
                "{\"id\":\"%s\",\"method\":\"%s\",\"params\":%s}",
                requestId, method, paramsJson != null ? paramsJson : "{}");

        CompletableFuture<String> future = new CompletableFuture<>();
        pendingRequests.put(requestId, future);

        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(commandJson));
            }
            log.debug("→ [{}] {}: {}", deviceId, method, paramsJson);
            String result = future.get(COMMAND_TIMEOUT, TimeUnit.SECONDS);
            log.debug("← [{}] {} = {}", deviceId, method,
                    result.length() > 200 ? result.substring(0, 200) + "..." : result);
            return result;
        } catch (TimeoutException e) {
            pendingRequests.remove(requestId);
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 超时（" + COMMAND_TIMEOUT + "s），设备 [" + deviceId + "] 未响应");
        } catch (Exception e) {
            pendingRequests.remove(requestId);
            throw new ToolExecutor.ToolExecuteException(
                    "命令 [" + method + "] 发送失败: " + e.getMessage());
        }
    }

    // ==================== WebSocket 事件处理 ====================

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("设备连接中: {} (session={})", session.getRemoteAddress(), session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String payload = message.getPayload();
        log.debug("收到设备消息: {}", payload.length() > 300 ? payload.substring(0, 300) + "..." : payload);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> msg = objectMapper.readValue(payload, Map.class);

            String type = (String) msg.get("type");

            // 注册消息
            if ("register".equals(type)) {
                String deviceId = (String) msg.get("deviceId");
                String deviceName = (String) msg.get("deviceName");
                if (deviceId == null || deviceId.isEmpty()) {
                    log.warn("设备注册失败：缺少 deviceId");
                    session.close(CloseStatus.BAD_DATA);
                    return;
                }
                // 如果已有同 deviceId 的连接，先关闭旧的
                WebSocketSession old = devices.remove(deviceId);
                if (old != null && old.isOpen()) {
                    try { old.close(); } catch (IOException ignored) {}
                }
                devices.put(deviceId, session);
                log.info("设备已注册: {} ({})，当前 {} 台设备在线",
                        deviceId, deviceName, devices.size());
                return;
            }

            // 心跳
            if ("heartbeat".equals(type)) {
                return;
            }

            // 命令响应（有 id 字段）
            String id = (String) msg.get("id");
            if (id != null) {
                CompletableFuture<String> future = pendingRequests.remove(id);
                if (future != null) {
                    String result = (String) msg.get("result");
                    String error = (String) msg.get("error");
                    if (error != null) {
                        future.complete("错误: " + error);
                    } else {
                        future.complete(result != null ? result : "ok");
                    }
                } else {
                    log.warn("收到未知请求的响应: id={}", id);
                }
            }
        } catch (Exception e) {
            log.error("解析设备消息失败: {}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        devices.values().removeIf(s -> s.getId().equals(session.getId()));
        log.info("设备已断开: session={}, status={}, 当前 {} 台设备在线",
                session.getId(), status, devices.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("设备连接传输错误 [{}]: {}", session.getId(), exception.getMessage());
        devices.values().removeIf(s -> s.getId().equals(session.getId()));
    }
}
