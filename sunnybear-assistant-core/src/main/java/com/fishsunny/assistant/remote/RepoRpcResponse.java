package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 响应帧 —— 云端执行结果，id 与请求一一对应
 *
 * ok=false 时 result 为 null、error 带异常字符串（丢的是异常类型，本地统一抛 RepoRpcException）。
 * 不支持把任意异常类型跨端还原：不需要，个人项目里调用方只关心"失败原因"。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

public record RepoRpcResponse(String id, boolean ok, Object result, String error) {
}
