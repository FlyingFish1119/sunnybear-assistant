package com.fishsunny.assistant.remote;

/*
 * @Usage 远程仓库代理工厂 —— 给任意 Repository 接口生成 JDK 动态代理，方法调用转发到 RepoRpcClient
 *
 * 关键点是"用接口声明来还原类型"：
 *  - 返回类型必须用 getGenericReturnType()，否则 List<ChatSession> 会退化成 List<LinkedHashMap>；
 *  - Object 自带方法（equals/hashCode/toString）不能当远端方法发出去，必须本地处理。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;

public final class RemoteRepositoryFactory {

    private RemoteRepositoryFactory() {
    }

    @SuppressWarnings("unchecked")
    public static <T> T create(Class<T> repositoryInterface, RepoRpcClient client, ObjectMapper objectMapper) {
        InvocationHandler handler = (proxy, method, args) -> {
            // equals/hashCode/toString 是代理自身的行为，不能发到云端
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "RemoteRepository<" + repositoryInterface.getSimpleName() + ">";
                    default -> null;
                };
            }
            // 用泛型返回类型：List<ChatSession> 才能还原成元素是 ChatSession，而不是 LinkedHashMap
            JavaType returnType = objectMapper.getTypeFactory().constructType(method.getGenericReturnType());
            Object result = client.call(repositoryInterface.getSimpleName(), method.getName(), args);
            // void / Void 方法没有 result，直接返回 null
            if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
                return null;
            }
            return objectMapper.convertValue(result, returnType);
        };

        return (T) Proxy.newProxyInstance(repositoryInterface.getClassLoader(), new Class<?>[]{repositoryInterface}, handler);
    }
}
