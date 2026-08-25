package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP Streamable HTTP 客户端传输/协议异常；McpClientService 捕获后转为业务异常上报
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import lombok.Getter;

/**
 * 覆盖两类错误：HTTP 层错误（非 2xx、超时等）与 JSON-RPC error 对象（见 {@link McpRpcException}）。
 * SessionExpiredException 仅作内部重连信号，不对外暴露。
 */
public class McpStreamHttpException extends RuntimeException {

    public McpStreamHttpException(String message) {
        super(message);
    }

    public McpStreamHttpException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * JSON-RPC error 对象（{code, message, data}）引发的异常。
     * 与 McpCallResult.isError 不同：这里指调用链路上的协议错误（如方法不存在、参数非法）。
     */
    @Getter
    public static class McpRpcException extends McpStreamHttpException {

        private final int code;

        public McpRpcException(int code, String message, Object data) {
            super("MCP RPC 错误 [code=" + code + "]: " + message + (data == null ? "" : "，data: " + data));
            this.code = code;
        }

    }

    /** 会话过期（HTTP 404/410）内部信号：由客户端捕获后重新握手并重试，不抛给上层 */
    static final class SessionExpiredException extends RuntimeException {
        SessionExpiredException() {
            super("MCP 会话已过期");
        }
    }
}
