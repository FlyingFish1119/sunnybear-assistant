package com.fishsunny.comfyui.network;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.comfyui.dispatch.CommandDispatcher;
import com.fishsunny.comfyui.protocol.CommandRequest;
import com.fishsunny.comfyui.protocol.CommandResponse;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Base64;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * WebSocket 客户端，连接 SunnyBear Server 的 /comfyui-bridge 端点。
 * <p>
 * 功能：
 * <ol>
 *   <li>连接后自动注册设备</li>
 *   <li>OkHttp 内置 ping 保持心跳</li>
 *   <li>断线 5 秒后自动重连</li>
 *   <li>收到命令 → CommandDispatcher 执行 → 返回结果</li>
 * </ol>
 */
public class WebSocketClient {

    private static final Logger log = LoggerFactory.getLogger(WebSocketClient.class);

    private final String serverUrl;
    private final String deviceId;
    private final String deviceName;
    private final String basicAuth;
    private final CommandDispatcher dispatcher;
    private final ObjectMapper objectMapper;

    private final OkHttpClient httpClient;
    private volatile WebSocket webSocket;
    private volatile boolean shouldReconnect = false;
    private volatile boolean connected = false;

    private final ScheduledExecutorService reconnectScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "comfyui-reconnect");
        t.setDaemon(true);
        return t;
    });

    public WebSocketClient(String serverUrl, String deviceId, String deviceName,
                           String username, String password,
                           CommandDispatcher dispatcher) {
        this.serverUrl = serverUrl;
        this.deviceId = deviceId;
        this.deviceName = deviceName;
        this.dispatcher = dispatcher;
        this.objectMapper = new ObjectMapper();

        // 构建 Basic Auth
        if (username != null && !username.isEmpty() && password != null && !password.isEmpty()) {
            String credentials = username + ":" + password;
            this.basicAuth = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        } else {
            this.basicAuth = null;
        }

        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)  // 无限等待
                .writeTimeout(10, TimeUnit.SECONDS)
                .pingInterval(30, TimeUnit.SECONDS)      // 30s ping 保持连接
                .build();
    }

    // ==================== 连接管理 ====================

    public void connect() {
        shouldReconnect = true;
        doConnect();
    }

    public void disconnect() {
        shouldReconnect = false;
        connected = false;
        if (webSocket != null) {
            webSocket.close(1000, "用户断开");
            webSocket = null;
        }
        log.info("已断开连接");
    }

    public boolean isConnected() {
        return connected;
    }

    // ==================== WebSocket 核心 ====================

    private void doConnect() {
        if (serverUrl == null || serverUrl.isEmpty()) {
            log.warn("服务器地址为空，跳过连接");
            return;
        }

        log.info("正在连接: {}", serverUrl);

        Request.Builder requestBuilder = new Request.Builder().url(serverUrl);
        if (basicAuth != null) {
            requestBuilder.addHeader("Authorization", basicAuth);
        }
        Request request = requestBuilder.build();

        webSocket = httpClient.newWebSocket(request, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response response) {
                connected = true;
                log.info("WebSocket 已连接 → {}", serverUrl);

                // 发送注册消息
                CommandResponse register = CommandResponse.register(deviceId, deviceName);
                ws.send(toJson(register));
                log.info("已注册设备: {} ({})", deviceId, deviceName);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                log.debug("收到命令: {}", text.length() > 200 ? text.substring(0, 200) + "..." : text);
                try {
                    CommandRequest cmd = objectMapper.readValue(text, CommandRequest.class);
                    if (cmd.id != null && cmd.method != null) {
                        String result = dispatcher.dispatch(cmd);
                        CommandResponse resp = CommandResponse.success(cmd.id, result);
                        ws.send(toJson(resp));
                        log.debug("命令完成: {} → result 长度={}", cmd.method, result.length());
                    }
                } catch (Exception e) {
                    log.error("处理命令失败: {}", text, e);
                    try {
                        // 尝试解析 id
                        @SuppressWarnings("unchecked")
                        Map<String, Object> msg = objectMapper.readValue(text, Map.class);
                        String id = (String) msg.get("id");
                        if (id != null) {
                            CommandResponse resp = CommandResponse.failure(id, e.getMessage());
                            ws.send(toJson(resp));
                        }
                    } catch (Exception ignored) {}
                }
            }

            @Override
            public void onClosing(WebSocket ws, int code, String reason) {
                log.info("WebSocket 关闭中: {} {}", code, reason);
                ws.close(1000, null);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.info("WebSocket 已关闭: {} {}", code, reason);
                connected = false;
                scheduleReconnect();
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response response) {
                log.error("WebSocket 连接失败: {}", t.getMessage());
                connected = false;
                scheduleReconnect();
            }
        });
    }

    private void scheduleReconnect() {
        if (!shouldReconnect) return;
        log.info("5 秒后重连...");
        reconnectScheduler.schedule(() -> {
            if (shouldReconnect && !connected) {
                doConnect();
            }
        }, 5, TimeUnit.SECONDS);
    }

    // ==================== 辅助 ====================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.error("JSON 序列化失败", e);
            return "{}";
        }
    }
}
