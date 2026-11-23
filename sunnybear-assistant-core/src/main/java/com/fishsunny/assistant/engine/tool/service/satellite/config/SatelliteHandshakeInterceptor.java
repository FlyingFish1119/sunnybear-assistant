package com.fishsunny.assistant.engine.tool.service.satellite.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * /satellite-bridge 端点的握手拦截器 —— Basic 鉴权。
 * <p>
 * username 与 password（plug.satellite.bridge.*）都配置时校验请求头
 * {@code Authorization: Basic ...}，不匹配直接拒绝握手；
 * 任一留空则跳过校验（鉴权可继续交给外层 Nginx）。
 */
@Slf4j
@Component
public class SatelliteHandshakeInterceptor implements HandshakeInterceptor {

    @Value("${plug.satellite.bridge.username:}")
    private String username;

    @Value("${plug.satellite.bridge.password:}")
    private String password;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        if (!StringUtils.hasText(username) || !StringUtils.hasText(password)) {
            // 未配置凭据：跳过校验，由外层反向代理或网络层负责
            return true;
        }

        HttpHeaders headers = request.getHeaders();
        String authorization = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authorization == null || !authorization.startsWith("Basic ")) {
            return reject(response, "缺少 Basic Authorization 头");
        }

        String expected = "Basic " + Base64.getEncoder()
                .encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
        if (!expected.equals(authorization)) {
            return reject(response, "Basic 凭据不匹配");
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // 建连结果由 SatelliteBridgeService 的连接回调记录，这里不重复
    }

    private boolean reject(ServerHttpResponse response, String reason) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.setStatusCode(HttpStatus.UNAUTHORIZED);
        }
        log.warn("卫星机握手被拒绝：{}", reason);
        return false;
    }
}
