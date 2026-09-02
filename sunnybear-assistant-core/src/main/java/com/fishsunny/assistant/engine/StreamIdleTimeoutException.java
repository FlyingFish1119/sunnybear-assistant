package com.fishsunny.assistant.engine;

/**
 * 流空闲超时异常：对端长时间未发送有效消息（完全静默或只发 keep-alive 等保活事件），
 * 本次流式连接被主动放弃并断开时抛出。
 */
public class StreamIdleTimeoutException extends RuntimeException {

    public StreamIdleTimeoutException(int idleSeconds, String adapterName) {
        super("对端超过 " + idleSeconds + " 秒未发送有效消息" +
                (adapterName != null ? "（适配器: " + adapterName + "）" : "") +
                "，已放弃本次流式连接并断开");
    }
}
