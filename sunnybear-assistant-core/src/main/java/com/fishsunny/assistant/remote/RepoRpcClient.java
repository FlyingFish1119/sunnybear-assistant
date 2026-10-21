package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 客户端 —— 只管发请求和等结果，线路怎么来的一概不问
 *
 * 会话从 RepoLink 拿：那条线是本机连出去的还是别人连进来的，客户端完全不知道也不需要知道。
 * 这样"remote 永远发请求、local 永远执行"就由角色保证，而不是由连接方向保证。
 * 超时与在途请求的失败由帧处理器统一收口（Client 发请求前 arm、结束后 disarm）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.remote.config.RemoteRepositoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class RepoRpcClient {

    private final ObjectMapper objectMapper;
    private final RemoteRepositoryProperties properties;
    private final RepoLink repoLink;
    private final RepoRpcFrameHandler frameHandler;

    public RepoRpcClient(ObjectMapper objectMapper,
                         RemoteRepositoryProperties properties,
                         RepoLink repoLink,
                         RepoRpcFrameHandler frameHandler) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.repoLink = repoLink;
        this.frameHandler = frameHandler;
    }

    /**
     * 发起一次同步 RPC。返回响应的 result（已确认 ok=true）；
     * 失败/超时/连接不上统一抛 {@link RepoRpcException}。
     */
    public Object call(String repo, String method, Object[] args) {
        String id = UUID.randomUUID().toString();
        CompletableFuture<RepoRpcResponse> future = frameHandler.arm(id);
        try {
            send(id, repo, method, args);
            RepoRpcResponse response = future.get(timeoutMs(), TimeUnit.MILLISECONDS);
            if (!response.ok()) {
                throw new RepoRpcException("远端仓储调用失败 " + repo + "." + method + ": " + response.error());
            }
            return response.result();
        } catch (TimeoutException e) {
            throw new RepoRpcException("仓储 RPC 超时(" + timeoutMs() + "ms): " + repo + "." + method, e);
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
            frameHandler.disarm(id);
        }
    }

    private void send(String id, String repo, String method, Object[] args) {
        WebSocketSession session = repoLink.acquire();
        List<Object> argList = args == null ? List.of() : Arrays.asList(args);
        String payload;
        try {
            payload = objectMapper.writeValueAsString(new RepoRpcRequest(id, repo, method, argList));
        } catch (JsonProcessingException e) {
            throw new RepoRpcException("序列化仓储 RPC 请求失败: " + repo + "." + method, e);
        }
        try {
            RepoLink.sendQuietly(session, payload);
        } catch (IOException e) {
            // 连接可能已断，剔掉让连接器去重连
            repoLink.invalidate(session);
            throw new RepoRpcException("发送仓储 RPC 请求失败: " + repo + "." + method, e);
        }
    }

    private long timeoutMs() {
        return properties.getTimeoutMs() == null ? 15000L : properties.getTimeoutMs();
    }
}
