package com.fishsunny.assistant.websocket;

/*
 * @Usage 会话消息总线 —— 把"投递"从"连接"上解耦：
 *        消息按 chatSessionId 发布，广播给该会话的所有订阅连接；
 *        同时缓存当前进行中一轮的事件，供重连连接订阅续传。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/29
 */

import com.fishsunny.assistant.constants.ControlSign;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class SessionMessageBus {

    /** 会话ID → 总线 */
    private final Map<String, Bus> buses = new ConcurrentHashMap<>();

    /**
     * 单个会话的总线。
     * buffer 只缓存"当前进行中一轮"的事件，SIGN_END 发布或 reset 时清空；
     * 已完成的轮次由 DB 兜底，无需在内存中保留。
     */
    static final class Bus {
        final List<Event> buffer = new ArrayList<>();
        final Set<WebSocketSession> subscribers = ConcurrentHashMap.newKeySet();
        /** 一轮是否仍在进行中（首次 publish 置 true，SIGN_END/reset 后置 false），用于防止总线条目被过早回收 */
        boolean active;
        long seq;
    }

    public record Event(long seq, String payload) {
    }

    /**
     * 发布一条会话级消息：追加到缓冲 + 广播给所有订阅连接。
     * append 与 broadcast 在同一把锁内完成，配合 subscribe 的快照读取，保证订阅续传与实时推送不重不漏。
     */
    public void publish(String sessionId, String payload) {
        Bus bus = buses.computeIfAbsent(sessionId, k -> new Bus());
        boolean isEnd = payload.startsWith(ControlSign.SIGN_END);
        synchronized (bus) {
            bus.buffer.add(new Event(++bus.seq, payload));
            bus.active = true;
            for (WebSocketSession subscriber : bus.subscribers) {
                try {
                    // 与直接发送方（SynchronizedWebSocketSession 锁同一 raw session）互斥，防 TEXT_PARTIAL_WRITING
                    synchronized (subscriber) {
                        subscriber.sendMessage(new TextMessage(payload));
                    }
                } catch (Exception e) {
                    log.warn("会话 [{}] 消息推送失败，移除订阅者: {}", sessionId, e.getMessage());
                    bus.subscribers.remove(subscriber);
                }
            }
            if (isEnd) {
                bus.buffer.clear();
                bus.active = false;
                if (bus.subscribers.isEmpty()) {
                    buses.remove(sessionId, bus);
                }
            }
        }
    }

    /**
     * 订阅会话：把连接加入订阅者集合，返回当前进行中一轮的已缓存事件快照（重连续传用）。
     */
    public List<Event> subscribe(String sessionId, WebSocketSession connection) {
        Bus bus = buses.computeIfAbsent(sessionId, k -> new Bus());
        synchronized (bus) {
            bus.subscribers.add(connection);
            return new ArrayList<>(bus.buffer);
        }
    }

    /**
     * 独占订阅：先把连接从其他所有会话总线上退订，再订阅当前会话。
     * 保证一个连接同一时刻只订阅一个会话（打开哪个会话就收哪个会话的消息）。
     */
    public List<Event> subscribeExclusive(String sessionId, WebSocketSession connection) {
        for (Map.Entry<String, Bus> entry : buses.entrySet()) {
            if (sessionId.equals(entry.getKey())) {
                continue;
            }
            Bus bus = entry.getValue();
            synchronized (bus) {
                bus.subscribers.remove(connection);
                cleanupIfIdle(entry.getKey(), bus);
            }
        }
        return subscribe(sessionId, connection);
    }

    public void unsubscribe(String sessionId, WebSocketSession connection) {
        Bus bus = buses.get(sessionId);
        if (bus == null) {
            return;
        }
        synchronized (bus) {
            bus.subscribers.remove(connection);
            cleanupIfIdle(sessionId, bus);
        }
    }

    /** 连接关闭/传输错误时，从所有会话总线上退订 */
    public void unsubscribeAll(WebSocketSession connection) {
        for (Map.Entry<String, Bus> entry : buses.entrySet()) {
            Bus bus = entry.getValue();
            synchronized (bus) {
                bus.subscribers.remove(connection);
                cleanupIfIdle(entry.getKey(), bus);
            }
        }
    }

    /** 清空当前轮缓存（错误/中断路径用），无订阅者时移除总线条目 */
    public void reset(String sessionId) {
        Bus bus = buses.get(sessionId);
        if (bus == null) {
            return;
        }
        synchronized (bus) {
            bus.buffer.clear();
            bus.active = false;
            if (bus.subscribers.isEmpty()) {
                buses.remove(sessionId, bus);
            }
        }
    }

    /** 非进行中且无订阅者且缓冲为空 → 移除总线条目，防止内存残留 */
    private void cleanupIfIdle(String sessionId, Bus bus) {
        if (!bus.active && bus.buffer.isEmpty() && bus.subscribers.isEmpty()) {
            buses.remove(sessionId, bus);
        }
    }

    /**
     * 把连接包装为"发布到总线"的会话对象，供工具 context 使用。
     * 工具通过 session.sendMessage(...) 发送的消息会写入总线 → 广播给该会话所有订阅连接（重连客户端也能收到）。
     */
    public WebSocketSession wrap(WebSocketSession delegate, String sessionId) {
        return new BusSession(delegate, sessionId);
    }

    /** 只把消息转发到总线的轻量包装器；其余方法委派给真实连接（isOpen 等仍可用） */
    private final class BusSession implements WebSocketSession {

        private final WebSocketSession delegate;
        private final String sessionId;

        private BusSession(WebSocketSession delegate, String sessionId) {
            this.delegate = delegate;
            this.sessionId = sessionId;
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) throws IOException {
            if (message instanceof TextMessage textMessage) {
                publish(sessionId, textMessage.getPayload());
            } else {
                synchronized (delegate) {
                    delegate.sendMessage(message);
                }
            }
        }

        // ===== 以下全部委派 =====

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public URI getUri() {
            return delegate.getUri();
        }

        @Override
        public HttpHeaders getHandshakeHeaders() {
            return delegate.getHandshakeHeaders();
        }

        @Override
        public Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public Principal getPrincipal() {
            return delegate.getPrincipal();
        }

        @Override
        public InetSocketAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public InetSocketAddress getRemoteAddress() {
            return delegate.getRemoteAddress();
        }

        @Override
        public String getAcceptedProtocol() {
            return delegate.getAcceptedProtocol();
        }

        @Override
        public void setTextMessageSizeLimit(int limit) {
            delegate.setTextMessageSizeLimit(limit);
        }

        @Override
        public int getTextMessageSizeLimit() {
            return delegate.getTextMessageSizeLimit();
        }

        @Override
        public void setBinaryMessageSizeLimit(int limit) {
            delegate.setBinaryMessageSizeLimit(limit);
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return delegate.getBinaryMessageSizeLimit();
        }

        @Override
        public List<WebSocketExtension> getExtensions() {
            return delegate.getExtensions();
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public void close() throws IOException {
            delegate.close();
        }

        @Override
        public void close(CloseStatus status) throws IOException {
            delegate.close(status);
        }
    }
}
