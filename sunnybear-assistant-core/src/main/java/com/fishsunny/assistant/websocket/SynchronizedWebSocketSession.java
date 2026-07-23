package com.fishsunny.assistant.websocket;

/*
 * @Usage WebSocketSession 同步包装器 —— 对 sendMessage 加锁，防止多线程并发写入同一个连接时抛出 TEXT_PARTIAL_WRITING
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/30
 */

import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketExtension;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.List;
import java.util.Map;

/**
 * 包装 WebSocketSession，对 sendMessage() 做同步串行化。
 * 其余方法直接委派给原始 session，不做额外处理。
 */
public class SynchronizedWebSocketSession implements WebSocketSession {

    private final WebSocketSession delegate;

    public SynchronizedWebSocketSession(WebSocketSession delegate) {
        this.delegate = delegate;
    }

    @Override
    public void sendMessage(WebSocketMessage<?> message) throws IOException {
        synchronized (delegate) {
            delegate.sendMessage(message);
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
