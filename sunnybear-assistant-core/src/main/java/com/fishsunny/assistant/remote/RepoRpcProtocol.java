package com.fishsunny.assistant.remote;

/*
 * @Usage 仓储 RPC 协议常量 —— 心跳帧这类非业务控制帧的约定
 *
 * 心跳帧是纯文本 ":keep-alive"（不是 JSON），两端在处理业务帧之前先识别它，
 * 客户端靠它把空闲的长连接保活，服务端收到后原样回一帧作为 pong。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

public final class RepoRpcProtocol {

    /** 心跳帧内容；客户端定时发送，服务端收到后原样回一帧 */
    public static final String KEEP_ALIVE = ":keep-alive";

    private RepoRpcProtocol() {
    }
}
