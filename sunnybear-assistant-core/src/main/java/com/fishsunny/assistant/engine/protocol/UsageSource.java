package com.fishsunny.assistant.engine.protocol;

/**
 * 可提供 token 用量的协议响应。
 *
 * <p>由各协议响应类实现，把自身携带的 usage 转成协议无关的 {@link TokenUsage}；
 * 主链路（ChatHttpHandler）不感知具体协议，只按本接口取值。无用量时返回 null。
 */
public interface UsageSource {

    TokenUsage toTokenUsage();
}
