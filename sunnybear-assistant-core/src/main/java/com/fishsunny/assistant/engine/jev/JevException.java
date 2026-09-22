package com.fishsunny.assistant.engine.jev;

/**
 * Jev 调用异常：承载 HTTP 状态码与可读的错误详情，便于上层按码分流
 * （401 配置问题 / 422 报文问题 / 429、529 可退避重试）。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:40
 */
public class JevException extends RuntimeException {

    /**
     * HTTP 状态码；非 HTTP 层面的失败（建连、序列化等）为 -1。
     */
    private final int statusCode;

    public JevException(String message) {
        this(message, -1, null);
    }

    public JevException(String message, Throwable cause) {
        this(message, -1, cause);
    }

    public JevException(String message, int statusCode, Throwable cause) {
        super(message, cause);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }

    /**
     * 是否属于「稍后重试有意义」的失败：限流或服务过载。
     */
    public boolean isRetryable() {
        return statusCode == 429 || statusCode == 529;
    }
}
