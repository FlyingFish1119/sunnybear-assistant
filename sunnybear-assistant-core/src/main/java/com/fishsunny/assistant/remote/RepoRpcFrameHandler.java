package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 帧处理器 —— 同一份代码，行为由本机 role 决定
 *
 * 这是整次改造的关键：帧怎么处理不再看"这条连接是连出去还是被连进来"，只看本机是 remote 还是 local。
 *   role=local  收到的就是请求帧：反射派发到容器里真实的 Repository bean，执行完把结果包成响应帧吐回去
 *   role=remote 收到的就是响应帧：按 id 配回在途请求的 CompletableFuture
 *
 * 因为 role 是单值，一台机器不可能同时是两种角色，所以帧类型由角色唯一确定，不需要在帧里塞 type 字段。
 * 解析后仍做一次字段兜底（请求必须有 repo/method，响应必须有 ok），配置错乱时丢帧加警告，不会误执行。
 *
 * pending 表放在这里而不是客户端里，是为了让依赖单向：Client → RepoLink → FrameHandler，
 * 三者谁都不用反过来引用谁，装配时不会绕成环。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/21
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.remote.config.RemoteRepositoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class RepoRpcFrameHandler {

    private final ObjectMapper objectMapper;
    private final RemoteRepositoryRegistry repositoryRegistry;
    private final RemoteRepositoryProperties properties;

    /** 请求 id → 等待响应的将来，用于把返回帧配回发起调用的线程（多路复用） */
    private final ConcurrentMap<String, CompletableFuture<RepoRpcResponse>> pending = new ConcurrentHashMap<>();

    public RepoRpcFrameHandler(ObjectMapper objectMapper,
                               RemoteRepositoryRegistry repositoryRegistry,
                               RemoteRepositoryProperties properties) {
        this.objectMapper = objectMapper;
        this.repositoryRegistry = repositoryRegistry;
        this.properties = properties;
    }

    /** 发请求之前先登记一个将来；超时或结束时由调用方 disarm 掉 */
    public CompletableFuture<RepoRpcResponse> arm(String id) {
        CompletableFuture<RepoRpcResponse> future = new CompletableFuture<>();
        pending.put(id, future);
        return future;
    }

    public void disarm(String id) {
        pending.remove(id);
    }

    /** 通道断开时让在途请求立刻失败，不用白等到超时 */
    public void failAll(String reason) {
        if (pending.isEmpty()) {
            return;
        }
        int count = pending.size();
        RepoRpcException cause = new RepoRpcException(reason);
        pending.forEach((id, future) -> future.completeExceptionally(cause));
        pending.clear();
        log.warn("仓储 RPC 有 {} 个在途请求因通道断开而失败：{}", count, reason);
    }

    /**
     * 处理一帧，返回需要回写的帧；null 表示不用回。
     * 心跳帧不归这里管 —— 谁持有连接谁负责发/回，那是连接器的职责，不是帧语义。
     */
    public String onFrame(WebSocketSession session, String payload) {
        JsonNode frame;
        try {
            frame = objectMapper.readTree(payload);
        } catch (Exception e) {
            log.warn("仓储 RPC 帧不是合法 JSON，已丢弃: {}", e.getMessage());
            return null;
        }
        return properties.isLocal() ? onRequestFrame(frame) : onResponseFrame(frame);
    }

    private String onRequestFrame(JsonNode frame) {
        if (!frame.hasNonNull("repo") || !frame.hasNonNull("method")) {
            log.warn("local 角色收到不像请求的帧（缺 repo/method），已丢弃");
            return null;
        }
        RepoRpcRequest request;
        try {
            request = objectMapper.treeToValue(frame, RepoRpcRequest.class);
        } catch (Exception e) {
            // 连请求都还原不出来就没有 id 可回，只能丢弃
            log.warn("解析仓储 RPC 请求失败: {}", e.getMessage());
            return null;
        }
        return encode(execute(request));
    }

    private String onResponseFrame(JsonNode frame) {
        if (!frame.has("ok")) {
            log.warn("remote 角色收到不像响应的帧（缺 ok），已丢弃");
            return null;
        }
        RepoRpcResponse response;
        try {
            response = objectMapper.treeToValue(frame, RepoRpcResponse.class);
        } catch (Exception e) {
            log.warn("解析仓储 RPC 响应失败: {}", e.getMessage());
            return null;
        }
        String id = response.id();
        if (id == null) {
            log.warn("仓储 RPC 响应缺少 id，已丢弃");
            return null;
        }
        CompletableFuture<RepoRpcResponse> future = pending.get(id);
        if (future == null) {
            log.debug("忽略无人等待的仓储 RPC 响应: {}", id);
            return null;
        }
        future.complete(response);
        return null;
    }

    /**
     * 执行请求并包成响应。任何异常都不外抛 —— 失败也必须作为一帧回回去，
     * 否则发起方只能干等到超时，还不知道为什么。
     */
    RepoRpcResponse execute(RepoRpcRequest request) {
        try {
            Object result = dispatch(request);
            return new RepoRpcResponse(request.id(), true, result, null);
        } catch (Throwable t) {
            // 反射调用抛的是 InvocationTargetException，真正的原因是它的 cause
            Throwable cause = t instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : t;
            log.warn("仓储 RPC 执行失败 {}.{}: {}", request.repo(), request.method(), cause.toString());
            return new RepoRpcResponse(request.id(), false, null, cause.toString());
        }
    }

    private Object dispatch(RepoRpcRequest request) throws Exception {
        Object bean = repositoryRegistry.getRepositoryInstance(request.repo());
        if (bean == null) {
            throw new IllegalStateException("未找到仓储 bean: " + request.repo());
        }
        List<Object> args = request.args() == null ? List.of() : request.args();
        // 按方法名 + 参数数定位。本项目的 Repository 没有重载，够用；有重载需再按类型消歧
        Method method = findMethod(bean.getClass(), request.method(), args.size());
        if (method == null) {
            throw new NoSuchMethodException(request.repo() + "." + request.method() + " 参数数 " + args.size());
        }
        // 关键：用接口上声明的参数类型（而不是运行时 bean 的类型）来还原每个参数。
        // 这样 int/long 会正确转回包装类型、Map<String,Object> 能还原成 Map、null 原样保持。
        Object[] converted = new Object[args.size()];
        for (int i = 0; i < args.size(); i++) {
            Type parameterType = method.getGenericParameterTypes()[i];
            converted[i] = objectMapper.convertValue(args.get(i), objectMapper.getTypeFactory().constructType(parameterType));
        }
        return method.invoke(bean, converted);
    }

    private Method findMethod(Class<?> clazz, String name, int argCount) {
        for (Method method : clazz.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == argCount) {
                return method;
            }
        }
        return null;
    }

    private String encode(RepoRpcResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            // 结果本身序列化不出来（比如返回了不可序列化的对象）时，退回一个纯错误帧
            log.warn("序列化仓储 RPC 响应失败: {}", e.getMessage());
            try {
                return objectMapper.writeValueAsString(
                        new RepoRpcResponse(response.id(), false, null, "响应序列化失败: " + e.getMessage()));
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
