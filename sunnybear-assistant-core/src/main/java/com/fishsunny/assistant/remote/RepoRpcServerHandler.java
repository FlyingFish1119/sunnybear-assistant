package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 服务端 —— 挂在 /ws/repo，把收到的 {仓库名,方法名,参数} 反射派发到容器里真实的 Repository bean
 *
 * 只在云端（权威库那一侧）真正执行 SQL，本地代理只发不收数据。因为两端是同一份 jar，
 * 接口名/方法签名/实体类完全一致，所以不需要维护任何 schema，直接按方法签名反序列化即可。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Type;
import java.util.List;

@Slf4j
@Component
public class RepoRpcServerHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper;
    private final RemoteRepositoryRegistry repositoryRegistry;

    public RepoRpcServerHandler(ObjectMapper objectMapper, RemoteRepositoryRegistry repositoryRegistry) {
        this.objectMapper = objectMapper;
        this.repositoryRegistry = repositoryRegistry;
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        String payload = message.getPayload();
        // 心跳帧不是 JSON，先识别并回一帧 pong，避免客户端因空闲被中间设备掐断
        if (RepoRpcProtocol.KEEP_ALIVE.equals(payload)) {
            try {
                session.sendMessage(new TextMessage(RepoRpcProtocol.KEEP_ALIVE));
            } catch (IOException e) {
                log.debug("回写仓储 RPC 心跳失败: {}", e.getMessage());
            }
            return;
        }

        RepoRpcRequest request;
        try {
            request = objectMapper.readValue(payload, RepoRpcRequest.class);
        } catch (Exception e) {
            // 连请求都解析不出来就没有 id 可回，只能丢弃
            log.warn("解析仓储 RPC 请求失败: {}", e.getMessage());
            return;
        }

        RepoRpcResponse response = handle(request);

        try {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
        } catch (IOException e) {
            log.warn("回写仓储 RPC 结果失败: {}", e.getMessage());
        }
    }

    /**
     * 执行请求并包成响应。单独抽出来是为了不依赖 WebSocketSession，方便测试直接调用。
     * 任何异常都不外抛——必须把失败也作为一帧响应回去，否则本地会一直等到超时。
     */
    RepoRpcResponse handle(RepoRpcRequest request) {
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
}
