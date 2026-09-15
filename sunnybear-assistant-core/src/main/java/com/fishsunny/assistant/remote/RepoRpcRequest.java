package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 请求帧 —— 本地代理把一次 Repository 方法调用摊平成 {仓库名, 方法名, 参数列表}
 *
 * id 由客户端生成，用于在多个并发请求中把响应配回对应的 CompletableFuture（客户端侧做多路复用）。
 * args 用 List<Object> 而非 Object[]：数组序列化后元素类型信息全丢，List 走 Jackson 泛型更自然。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

import java.util.List;

public record RepoRpcRequest(String id, String repo, String method, List<Object> args) {
}
