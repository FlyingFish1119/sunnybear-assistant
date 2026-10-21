package com.fishsunny.assistant.remote;

/*
 * @Usage /ws/repo 端点 —— 纯粹的接入点，不再等于"执行者"
 *
 * 它的职责只有两件：握手通过的会话交给连接器登记，收到的帧转给帧处理器。
 * 之所以不再自己解析业务，是因为这条线是谁连上来的已经不重要了 ——
 * 如果本机是 remote，这条被连进来的线照样可以用来发请求；如果本机是 local，就在这条线上执行 SQL。
 *
 * 心跳帧在这里回 pong：接受方永远是回 pong 的一侧，发起方负责定时发。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/21
 */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class RepoRpcEndpoint extends TextWebSocketHandler {

    private final RepoLink repoLink;
    private final RepoRpcFrameHandler frameHandler;

    @Override
    public void afterConnectionEstablished(@NonNull WebSocketSession session) {
        String peerRole = String.valueOf(session.getAttributes().get(RepoRpcHandshake.ATTR_PEER_ROLE));
        String peerNode = String.valueOf(session.getAttributes().get(RepoRpcHandshake.ATTR_PEER_NODE));
        repoLink.register(session, peerNode);
        log.info("仓储端点接入：对端 role={} node={}，来源 {}", peerRole, peerNode, remoteAddress(session));
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String payload = message.getPayload();
        // 接受方回 pong：发起方靠这一帧判定链路是否还活着
        if (RepoRpcProtocol.KEEP_ALIVE.equals(payload)) {
            try {
                RepoLink.sendQuietly(session, RepoRpcProtocol.KEEP_ALIVE);
            } catch (IOException e) {
                log.debug("回写仓储心跳失败：{}", e.getMessage());
            }
            return;
        }
        String reply = frameHandler.onFrame(session, payload);
        if (reply == null) {
            return;
        }
        try {
            RepoLink.sendQuietly(session, reply);
        } catch (IOException e) {
            log.warn("回写仓储 RPC 结果失败：{}", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(@NonNull WebSocketSession session, @NonNull CloseStatus status) {
        repoLink.invalidate(session);
        log.warn("仓储端点连接关闭：对端 node={}（{}）",
                session.getAttributes().get(RepoRpcHandshake.ATTR_PEER_NODE), status);
    }

    @Override
    public void handleTransportError(@NonNull WebSocketSession session, @NonNull Throwable exception) {
        log.warn("仓储端点连接异常：{}", exception.getMessage());
        repoLink.invalidate(session);
    }

    private String remoteAddress(WebSocketSession session) {
        return session.getRemoteAddress() == null ? "unknown" : session.getRemoteAddress().toString();
    }
}
