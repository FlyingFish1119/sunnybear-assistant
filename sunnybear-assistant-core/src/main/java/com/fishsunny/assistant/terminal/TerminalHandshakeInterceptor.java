package com.fishsunny.assistant.terminal;

/*
 * @Usage 终端 WebSocket 握手拦截器 —— 来源校验。
 *
 *        终端能执行任意命令，而服务绑的是 0.0.0.0，局域网里任何设备都能访问该端口。
 *        沿用旧 /shell 的策略：只放行回环来源，非本机在握手阶段直接拒绝（403），
 *        避免「能访问端口」直接等价于「能在本机执行命令」。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28
 */

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;
import java.util.Set;

@Slf4j
@Component
public class TerminalHandshakeInterceptor implements HandshakeInterceptor {

    private static final Set<String> LOCAL_ADDRESSES = Set.of(
            "127.0.0.1", "::1", "0:0:0:0:0:0:0:1", "localhost"
    );

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String address = resolveRemoteAddress(request);
        if (address != null && LOCAL_ADDRESSES.contains(address)) {
            return true;
        }
        log.warn("拒绝非本机来源的终端连接: remoteAddr={}", address);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // 无需处理
    }

    private String resolveRemoteAddress(ServerHttpRequest request) {
        if (request instanceof ServletServerHttpRequest servletRequest) {
            return servletRequest.getServletRequest().getRemoteAddr();
        }
        request.getRemoteAddress();
        if (request.getRemoteAddress().getAddress() != null) {
            return request.getRemoteAddress().getAddress().getHostAddress();
        }
        return null;
    }
}
