package com.fishsunny.assistant.remote;

/*
 * @Usage 远程仓储调用异常 —— 连接失败 / 超时 / 云端执行报错统一抛这个
 *
 * 继承 RuntimeException：Repository 方法本身不受检，调用方不需要额外 try。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/15
 */

public class RepoRpcException extends RuntimeException {

    public RepoRpcException(String message) {
        super(message);
    }

    public RepoRpcException(String message, Throwable cause) {
        super(message, cause);
    }
}
