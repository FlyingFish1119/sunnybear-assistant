package com.fishsunny.assistant.remote;

/*
 * @Usage /ws/repo 端点的握手拦截器 —— 校验对端身份，拒绝角色不互补的连接
 *
 * 端点本身只是会合点，谁来连都行；但两端角色必须一 remote 一 local，否则这条线上永远不会有人执行 SQL。
 * 校验不过直接回 403，并在日志里写清本机和对端的 role / node，配置写反时不用靠猜。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/21
 */

import com.fishsunny.assistant.remote.config.RemoteRepositoryProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class RepoRpcHandshakeInterceptor implements HandshakeInterceptor {

    private final RemoteRepositoryProperties properties;

    @Override
    public boolean beforeHandshake(@NonNull ServerHttpRequest request,
                                   @NonNull ServerHttpResponse response,
                                   @NonNull WebSocketHandler wsHandler,
                                   @NonNull Map<String, Object> attributes) {
        HttpHeaders headers = request.getHeaders();
        String peerRole = headers.getFirst(RepoRpcHandshake.HEADER_ROLE);
        String peerNode = headers.getFirst(RepoRpcHandshake.HEADER_NODE);
        String peerProtocol = headers.getFirst(RepoRpcHandshake.HEADER_PROTOCOL);

        if (!StringUtils.hasText(peerRole)) {
            return reject(response, HttpStatus.BAD_REQUEST, peerNode,
                    "缺少身份头 " + RepoRpcHandshake.HEADER_ROLE + "，对端可能还是旧版本或走了其它客户端");
        }
        if (!RepoRpcHandshake.PROTOCOL_VERSION.equals(peerProtocol)) {
            return reject(response, HttpStatus.BAD_REQUEST, peerNode,
                    "帧协议版本不匹配，本机 " + RepoRpcHandshake.PROTOCOL_VERSION + "，对端 " + peerProtocol);
        }

        String localRole = properties.getRole();
        if (localRole == null || localRole.equalsIgnoreCase(peerRole.trim())) {
            return reject(response, HttpStatus.FORBIDDEN, peerNode,
                    "两端角色相同（都是 " + peerRole.trim() + "），角色必须互补，一个 remote 一个 local");
        }

        attributes.put(RepoRpcHandshake.ATTR_PEER_ROLE, peerRole.trim().toLowerCase());
        attributes.put(RepoRpcHandshake.ATTR_PEER_NODE,
                StringUtils.hasText(peerNode) ? peerNode.trim() : "unknown");
        return true;
    }

    @Override
    public void afterHandshake(@NonNull ServerHttpRequest request,
                              @NonNull ServerHttpResponse response,
                              @NonNull WebSocketHandler wsHandler,
                              Exception exception) {
        // 建连结果由端点自己的 afterConnectionEstablished / afterConnectionClosed 记录，这里不重复
    }

    private boolean reject(ServerHttpResponse response, HttpStatus status, String peerNode, String reason) {
        if (response instanceof ServletServerHttpResponse servletResponse) {
            servletResponse.setStatusCode(status);
        }
        log.error("仓储连接被拒绝（本机 role={} node={}，对端 node={}）：{}",
                properties.getRole(), properties.getNode(),
                StringUtils.hasText(peerNode) ? peerNode : "unknown", reason);
        return false;
    }
}
