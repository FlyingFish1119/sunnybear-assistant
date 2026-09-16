package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP 客户端传输/协议异常；上层工具捕获后转为 ToolExecuteException 上报
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import lombok.Getter;

/**
 * 覆盖两类错误：传输层错误（非 2xx、超时、子进程异常退出等）与 JSON-RPC error 对象（见 {@link McpRpcException}）。
 */
public class McpException extends RuntimeException {

    public McpException(String message) {
        super(message);
    }

    public McpException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * JSON-RPC error 对象（{code, message, data}）引发的异常。
     * 与 McpCallResult.isError 不同：这里指调用链路上的协议错误（如方法不存在、参数非法）。
     */
    @Getter
    public static class McpRpcException extends McpException {

        private final int code;

        public McpRpcException(int code, String message, Object data) {
            super("MCP RPC 错误 [code=" + code + "]: " + message + (data == null ? "" : "，data: " + data));
            this.code = code;
        }

    }

    /**
     * 会话已失效的内部信号：由客户端捕获后重新建立传输并重试，不抛给上层。
     * HTTP 传输在收到 404/410 时抛出；stdio 传输在子进程退出时抛出。
     */
    static final class SessionExpiredException extends RuntimeException {

        SessionExpiredException() {
            super("MCP 会话已失效");
        }

    }
}
