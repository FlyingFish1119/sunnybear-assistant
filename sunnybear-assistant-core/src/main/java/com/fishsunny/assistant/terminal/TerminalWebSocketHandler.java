package com.fishsunny.assistant.terminal;

/*
 * @Usage 终端 WebSocket 端点 —— 浏览器 xterm.js 与本地 PTY 之间的双向管道。
 *
 *        协议：
 *        · 客户端 → 服务端：二进制帧 = 用户键入的原始字节，直接写进 PTY；
 *          文本帧 = JSON 控制消息（目前只有 {"type":"resize","cols":c,"rows":r}）。
 *        · 服务端 → 客户端：二进制帧 = PTY 原始输出，前端交给 xterm 渲染；
 *          文本帧 = JSON 状态（ready / exit / error），前端据此更新连接状态。
 *
 *        一个连接对应一个 PTY 进程：连接建立即 start，关闭即回收整棵进程树。
 *        尺寸优先从握手 query 取（cols / rows），前端拿到最终布局后还会再发一次 resize 校正。
 *
 *        来源校验在 {@link TerminalHandshakeInterceptor} 里完成（仅本机）。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class TerminalWebSocketHandler extends AbstractWebSocketHandler {

    private static final int DEFAULT_COLS = 80;
    private static final int DEFAULT_ROWS = 24;
    private static final int MIN_COLS = 2;
    private static final int MAX_COLS = 1000;
    private static final int MIN_ROWS = 1;
    private static final int MAX_ROWS = 1000;

    private final ObjectMapper objectMapper;
    private final Map<String, TerminalSession> sessions = new ConcurrentHashMap<>();

    public TerminalWebSocketHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        int cols = clamp(queryInt(session, "cols", DEFAULT_COLS), MIN_COLS, MAX_COLS);
        int rows = clamp(queryInt(session, "rows", DEFAULT_ROWS), MIN_ROWS, MAX_ROWS);

        TerminalSession terminal;
        try {
            terminal = TerminalSession.start(cols, rows,
                    bytes -> sendBinary(session, bytes),
                    code -> sendExit(session, code));
        } catch (Exception e) {
            log.error("启动本地终端失败 [{}]: {}", session.getId(), e.getMessage(), e);
            sendText(session, "error", "启动本地终端失败: " + e.getMessage(), null);
            closeQuietly(session, CloseStatus.SERVER_ERROR);
            return;
        }

        sessions.put(session.getId(), terminal);
        sendText(session, "ready", null, null);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        TerminalSession terminal = sessions.get(session.getId());
        if (terminal == null) {
            return;
        }
        ByteBuffer payload = message.getPayload();
        byte[] data = new byte[payload.remaining()];
        payload.get(data);
        terminal.write(data);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        TerminalSession terminal = sessions.get(session.getId());
        if (terminal == null) {
            return;
        }
        try {
            var node = objectMapper.readTree(message.getPayload());
            if (node != null && "resize".equals(node.path("type").asText())) {
                int cols = clamp(node.path("cols").asInt(DEFAULT_COLS), MIN_COLS, MAX_COLS);
                int rows = clamp(node.path("rows").asInt(DEFAULT_ROWS), MIN_ROWS, MAX_ROWS);
                terminal.resize(cols, rows);
            }
        } catch (Exception e) {
            log.debug("忽略无法解析的终端控制消息 [{}]: {}", session.getId(), e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        dispose(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("终端传输错误 [{}]: {}", session.getId(), exception.getMessage());
        dispose(session);
    }

    private void dispose(WebSocketSession session) {
        TerminalSession terminal = sessions.remove(session.getId());
        if (terminal != null) {
            terminal.close();
        }
    }

    /* ======================== 下发 ======================== */

    private void sendBinary(WebSocketSession session, byte[] bytes) {
        if (session.isOpen()) {
            synchronized (session) {
                try {
                    session.sendMessage(new BinaryMessage(bytes));
                } catch (IOException e) {
                    log.debug("终端输出发送失败 [{}]: {}", session.getId(), e.getMessage());
                }
            }
        }
    }

    private void sendExit(WebSocketSession session, int code) {
        Map<String, Object> extra = new LinkedHashMap<>();
        extra.put("code", code);
        sendText(session, "exit", null, extra);
    }

    /** 发送一条 JSON 文本控制帧：{"type":..., "message"?:..., ...extra} */
    private void sendText(WebSocketSession session, String type, String message, Map<String, Object> extra) {
        if (!session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("type", type);
            if (message != null) {
                payload.put("message", message);
            }
            if (extra != null) {
                payload.putAll(extra);
            }
            synchronized (session) {
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(payload)));
            }
        } catch (Exception e) {
            log.debug("终端控制帧发送失败 [{}]: {}", session.getId(), e.getMessage());
        }
    }

    /* ======================== 工具 ======================== */

    private int queryInt(WebSocketSession session, String key, int fallback) {
        if (session.getUri() == null || !StringUtils.hasText(session.getUri().getQuery())) {
            return fallback;
        }
        for (String pair : session.getUri().getQuery().split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            if (key.equals(pair.substring(0, eq))) {
                try {
                    return Integer.parseInt(pair.substring(eq + 1).trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    private int clamp(int value, int min, int max) {
        return Math.clamp(value, min, max);
    }

    private void closeQuietly(WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (IOException e) {
            log.debug("关闭终端连接失败 [{}]: {}", session.getId(), e.getMessage());
        }
    }
}
