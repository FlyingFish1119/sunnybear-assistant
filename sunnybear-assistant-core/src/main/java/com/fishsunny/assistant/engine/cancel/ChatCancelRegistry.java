package com.fishsunny.assistant.engine.cancel;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/*
 * @Usage 会话级取消注册表 —— 把「停止」从 sessionId 映射到当前在途轮次的取消令牌。
 *
 * 一个会话只保留一个在途令牌：begin 无条件覆盖，end 用条件移除防止误删。
 * 不做引用计数 —— 同一会话的对话链路已由 ChatProcessor 的分片锁串行化，
 * 不会出现两条链路抢同一个 key 的情况。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/20
 */
@Component
public class ChatCancelRegistry {

    /** 会话 key → 当前在途轮次的取消令牌 */
    private final Map<String, ChatCancelToken> tokens = new ConcurrentHashMap<>();

    /**
     * 开启一轮：注册并返回本轮的取消令牌。
     * 无条件覆盖 —— 万一上一轮的令牌因异常没走到注销，这里直接顶掉，
     * 避免新一轮拿到一个已被取消的令牌。
     */
    public ChatCancelToken begin(String key) {
        ChatCancelToken token = new ChatCancelToken();
        if (key != null) {
            tokens.put(key, token);
        }
        return token;
    }

    /**
     * 结束一轮。条件移除：注册表里已不是自己这个令牌时不动，
     * 避免上一轮的收尾把新一轮的令牌误删。
     */
    public void end(String key, ChatCancelToken token) {
        if (key != null && token != null) {
            tokens.remove(key, token);
        }
    }

    /** 取消该会话在途轮次。无在途轮次时静默返回 false（幂等，重复停止无害） */
    public boolean cancel(String key) {
        ChatCancelToken token = key == null ? null : tokens.get(key);
        if (token == null) {
            return false;
        }
        token.cancel();
        return true;
    }

    /** 该会话当前轮次是否已被取消 */
    public boolean isCancelled(String key) {
        ChatCancelToken token = key == null ? null : tokens.get(key);
        return token != null && token.isCancelled();
    }
}
