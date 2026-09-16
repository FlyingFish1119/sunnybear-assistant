package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP 客户端骨架：用模板方法焊死「懒握手 → JSON-RPC 请求/通知 → 会话失效重连」这条流程契约，
 *        把「消息怎么送出去、响应怎么收回来」完全交给子类。
 *        子类只需实现 open / exchange / send / closeTransport / transport 五个传输动作。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public abstract class AbstractMcpClient implements McpClient {

    /** 客户端声明支持的 MCP 协议版本；握手后以服务端协商返回的版本为准 */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** 会话失效后的重建次数上限（1 次初始 + 1 次重建），防止对端持续失效导致死循环 */
    private static final int MAX_RETRY = 2;

    protected final McpProperties.Client client;

    protected final ObjectMapper objectMapper;

    private final AtomicLong idSeq = new AtomicLong();

    private volatile boolean initialized;

    /** 握手协商出的协议版本，后续请求使用 */
    protected volatile String negotiatedProtocolVersion = PROTOCOL_VERSION;

    protected AbstractMcpClient(McpProperties.Client client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
    }

    @Override
    public McpListToolsResult listTools(String cursor) {
        return withReconnect(() -> {
            Map<String, Object> params = StringUtils.hasText(cursor) ? Map.of("cursor", cursor) : Map.of();
            return objectMapper.convertValue(request("tools/list", params), McpListToolsResult.class);
        });
    }

    @Override
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) {
        return withReconnect(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            return objectMapper.convertValue(request("tools/call", params), McpCallResult.class);
        });
    }

    @Override
    public void close() {
        closeTransport();
    }

    // ==================== 骨架流程（子类不参与） ====================

    /** 会话失效时重建传输并重试一次；其余异常直接向上抛，不重试 */
    private <T> T withReconnect(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                ensureInitialized();
                return action.get();
            } catch (McpException.SessionExpiredException e) {
                reset();
                if (++attempt >= MAX_RETRY) {
                    throw new McpException("MCP Server [" + serverName() + "] 会话失效且重建后仍失败", e);
                }
                log.info("MCP Server [{}] 会话失效，重建传输后重试", serverName());
            }
        }
    }

    /** 握手：initialize → 捕获协议版本 → 发送 initialized 通知；双重检查防并发重复握手 */
    private void ensureInitialized() {
        if (initialized) {
            return;
        }
        synchronized (this) {
            if (initialized) {
                return;
            }
            open();
            JsonNode result = request("initialize", Map.of(
                    "protocolVersion", PROTOCOL_VERSION,
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", client.getClientName(), "version", client.getClientVersion())));
            negotiatedProtocolVersion = result.path("protocolVersion").asText(PROTOCOL_VERSION);
            send(envelope("notifications/initialized", null, Map.of()));
            initialized = true;
            log.info("已连接 MCP Server: {}（transport={}，协商协议版本: {}）",
                    serverName(), transport(), negotiatedProtocolVersion);
        }
    }

    /** 失效当前会话，下次调用前重新握手；子类可覆写以顺带清理传输侧状态 */
    protected void reset() {
        initialized = false;
    }

    private JsonNode request(String method, Map<String, Object> params) {
        long id = idSeq.incrementAndGet();
        return extractResult(exchange(envelope(method, id, params)));
    }

    /** 构造 JSON-RPC 2.0 信封；id 为 null 表示通知 */
    protected JsonNode envelope(String method, Long id, Map<String, Object> params) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        if (id != null) {
            body.put("id", id);
        }
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);
        return objectMapper.valueToTree(body);
    }

    /** 从 JSON-RPC 信封提取 result；含 error 对象时抛 McpRpcException */
    protected JsonNode extractResult(JsonNode envelope) {
        if (envelope == null || !envelope.isObject()) {
            throw new McpException("MCP 响应格式错误，期望 JSON-RPC 对象");
        }
        if (envelope.has("error")) {
            JsonNode err = envelope.get("error");
            throw new McpException.McpRpcException(
                    err.path("code").asInt(-32603),
                    err.path("message").asText("未知错误"),
                    err.has("data") && !err.get("data").isNull() ? err.get("data") : null);
        }
        if (envelope.has("result")) {
            return envelope.get("result");
        }
        throw new McpException("MCP 响应既无 result 也无 error");
    }

    protected String serverName() {
        return client.getServerName();
    }

    protected long timeoutMillis() {
        Integer seconds = client.getTimeoutS();
        return (seconds == null || seconds <= 0 ? 20 : seconds) * 1000L;
    }

    protected JsonNode readTree(String text) {
        try {
            return objectMapper.readTree(text);
        } catch (JsonProcessingException e) {
            throw new McpException("响应体解析失败: " + e.getMessage(), e);
        }
    }

    // ==================== 传输动作（子类实现） ====================

    /** 建立底层传输；必须幂等，已建立时直接返回 */
    protected abstract void open();

    /** 发送带 id 的 JSON-RPC 请求，阻塞等待并返回其响应信封 */
    protected abstract JsonNode exchange(JsonNode envelope);

    /** 发送不带 id 的 JSON-RPC 通知，不等响应 */
    protected abstract void send(JsonNode envelope);

    /** 释放底层传输资源；必须幂等 */
    protected abstract void closeTransport();

    /** 传输名，仅用于日志 */
    protected abstract String transport();
}
