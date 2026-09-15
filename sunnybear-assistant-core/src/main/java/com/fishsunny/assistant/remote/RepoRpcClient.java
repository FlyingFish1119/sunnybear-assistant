package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 客户端 —— 本地把 Repository 调用经 WebSocket 发到云端，同步等结果
 *
 * 用完即走的短连接模型：连接惰性建立、断了下次调用自动重连，不做心跳保活
 * （单用户、几乎无并发，够用；要长期常驻再补 ping/pong）。
 * 并发安全：pending 用 ConcurrentMap，发送时 synchronized(session) 串行化，
 * 避免多线程同时写一个连接触发 TEXT_PARTIAL_WRITING（同 SynchronizedWebSocketSession 的思路）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class RepoRpcClient {

    private final ObjectMapper objectMapper;
    private final String url;
    private final long timeoutMs;
    private final StandardWebSocketClient webSocketClient = new StandardWebSocketClient();

    /** 请求 id → 等待响应的将来，用于把返回帧配回发起调用的线程（多路复用） */
    private final ConcurrentMap<String, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile WebSocketSession session;

    public RepoRpcClient(ObjectMapper objectMapper, String url, long timeoutMs) {
        this.objectMapper = objectMapper;
        this.url = url;
        this.timeoutMs = timeoutMs;
    }

    /**
     * 发起一次同步 RPC。返回 result 字段的 JsonNode（已确认 ok=true）；
     * 失败/超时/连接不上统一抛 {@link RepoRpcException}。
     */
    public JsonNode call(String repo, String method, Object[] args) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pending.put(id, future);
        try {
            send(id, repo, method, args);
            JsonNode frame = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (!frame.path("ok").asBoolean(false)) {
                throw new RepoRpcException("远端仓储调用失败 " + repo + "." + method + ": " + frame.path("error").asText());
            }
            return frame.get("result");
        } catch (TimeoutException e) {
            throw new RepoRpcException("仓储 RPC 超时(" + timeoutMs + "ms): " + repo + "." + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RepoRpcException("仓储 RPC 被中断: " + repo + "." + method, e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof RepoRpcException rpe) {
                throw rpe;
            }
            throw new RepoRpcException("仓储 RPC 执行异常: " + repo + "." + method, cause);
        } finally {
            pending.remove(id);
        }
    }

    private void send(String id, String repo, String method, Object[] args) {
        WebSocketSession current = ensureSession();
        List<Object> argList = args == null ? List.of() : Arrays.asList(args);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new RepoRpcRequest(id, repo, method, argList));
        } catch (JsonProcessingException e) {
            throw new RepoRpcException("序列化仓储 RPC 请求失败: " + repo + "." + method, e);
        }
        try {
            synchronized (current) {
                current.sendMessage(new TextMessage(payload));
            }
        } catch (IOException e) {
            // 连接可能已断，清掉让下次调用重连
            invalidate(current);
            throw new RepoRpcException("发送仓储 RPC 请求失败: " + repo + "." + method, e);
        }
    }

    /** 惰性建连、断开后自动重连；同一时刻只允许一个线程发起握手。 */
    private WebSocketSession ensureSession() {
        WebSocketSession current = session;
        if (current != null && current.isOpen()) {
            return current;
        }
        synchronized (this) {
            current = session;
            if (current != null && current.isOpen()) {
                return current;
            }
            try {
                WebSocketSession connected = webSocketClient.execute(new ClientHandler(), url)
                        .get(timeoutMs, TimeUnit.MILLISECONDS);
                session = connected;
                log.debug("仓储 RPC 已连接: {}", url);
                return connected;
            } catch (Exception e) {
                throw new RepoRpcException("连接仓储 RPC 服务端失败: " + url, e);
            }
        }
    }

    private void invalidate(WebSocketSession expected) {
        synchronized (this) {
            if (session == expected) {
                session = null;
            }
        }
    }

    /** 关闭长连接并让所有在途请求立即失败；之后再次 call() 会重新建连。 */
    public void close() {
        WebSocketSession current;
        synchronized (this) {
            current = session;
            session = null;
        }
        if (current != null) {
            try {
                current.close();
            } catch (IOException e) {
                log.debug("关闭仓储 RPC 连接失败: {}", e.getMessage());
            }
        }
    }

    /** 接收响应帧并按 id 唤醒对应的 CompletableFuture。 */
    private class ClientHandler extends TextWebSocketHandler {

        @Override
        protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
            JsonNode frame;
            try {
                frame = objectMapper.readTree(message.getPayload());
            } catch (Exception e) {
                log.warn("解析仓储 RPC 响应失败: {}", e.getMessage());
                return;
            }
            String id = frame.path("id").asText(null);
            if (id == null) {
                log.warn("仓储 RPC 响应缺少 id，已丢弃");
                return;
            }
            CompletableFuture<JsonNode> future = pending.get(id);
            if (future == null) {
                log.debug("忽略无人等待的仓储 RPC 响应: {}", id);
                return;
            }
            future.complete(frame);
        }

        @Override
        public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
            RepoRpcClient.this.invalidate(session);
            // 连接没了，在途请求不可能再有响应，立即失败避免白等到超时
            RepoRpcException cause = new RepoRpcException("仓储 RPC 连接已关闭: " + status);
            pending.forEach((id, future) -> future.completeExceptionally(cause));
            log.warn("仓储 RPC 连接已关闭: {}", status);
        }

        @Override
        public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
            log.warn("仓储 RPC 连接异常: {}", exception.getMessage());
        }
    }
}
