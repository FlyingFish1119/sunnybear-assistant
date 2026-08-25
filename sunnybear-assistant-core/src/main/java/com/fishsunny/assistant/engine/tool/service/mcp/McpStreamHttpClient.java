package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP Streamable HTTP 客户端 —— 用 java.net.http.HttpClient 实现的简易 JSON-RPC 2.0 客户端，
 *        只覆盖本项目用到的能力：initialize 握手、notifications/initialized、tools/list（含分页）、tools/call。
 *        支持懒连接、会话复用与会话过期（HTTP 404/410）自动重建。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25
 */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

@Slf4j
public class McpStreamHttpClient {

    /** 客户端声明支持的 MCP 协议版本；握手后以服务端协商返回的版本为准 */
    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** 告知服务端可同时接受 JSON 与 SSE 两种响应体 */
    private static final String ACCEPT_HEADER = "application/json, text/event-stream";

    /** 会话标识头：握手后从响应头捕获并回传 */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    /** 会话过期后的重连次数上限（1 次初始 + 1 次重连），防止服务端持续失效导致死循环 */
    private static final int MAX_RETRY = 2;

    private final McpProperties.Client client;
    private final McpTokenManager tokenManager;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    private final AtomicLong idSeq = new AtomicLong();

    /** 握手后非空；随每次响应头刷新 */
    private volatile String sessionId;

    /** 懒连接完成标志 */
    private volatile boolean initialized;

    /** 握手协商出的协议版本，后续请求头使用 */
    private volatile String negotiatedProtocolVersion = PROTOCOL_VERSION;

    public McpStreamHttpClient(McpProperties.Client client, McpTokenManager tokenManager,
                               HttpClient httpClient, ObjectMapper objectMapper) {
        this.client = client;
        this.tokenManager = tokenManager;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 查询工具清单（单页）；cursor 非空时请求下一页 */
    public McpListToolsResult listTools(String cursor) {
        return withReconnect(() -> {
            Map<String, Object> params = StringUtils.hasText(cursor) ? Map.of("cursor", cursor) : Map.of();
            JsonNode result = request("tools/list", params);
            return objectMapper.convertValue(result, McpListToolsResult.class);
        });
    }

    /** 调用远程工具；arguments 为 null 时按空对象传递 */
    public McpCallResult callTool(String toolName, Map<String, Object> arguments) {
        return withReconnect(() -> {
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", toolName);
            params.put("arguments", arguments == null ? Map.of() : arguments);
            JsonNode result = request("tools/call", params);
            return objectMapper.convertValue(result, McpCallResult.class);
        });
    }

    /**
     * 会话过期（SessionExpiredException）时失效当前会话并重新握手重试一次。
     * 其余异常（McpRpcException / McpStreamHttpException）直接向上抛，不重试。
     */
    private <T> T withReconnect(Supplier<T> action) {
        int attempt = 0;
        while (true) {
            try {
                ensureInitialized();
                return action.get();
            } catch (McpStreamHttpException.SessionExpiredException e) {
                invalidateSession();
                if (++attempt >= MAX_RETRY) {
                    throw new McpStreamHttpException(
                            "MCP Server [" + client.getServerName() + "] 会话过期且重连后仍失败", e);
                }
                log.info("MCP Server [{}] 会话过期，重新握手后重试", client.getServerName());
            }
        }
    }

    private void ensureInitialized() {
        if (!initialized) {
            connect();
        }
    }

    /** 握手：initialize → 捕获会话 id 与协议版本 → 发送 initialized 通知。锁内二次检查防并发重复握手 */
    private synchronized void connect() {
        if (initialized) {
            return;
        }
        sessionId = null; // 全新会话，清掉旧 id
        JsonNode result = request("initialize", Map.of(
                "protocolVersion", PROTOCOL_VERSION,
                "capabilities", Map.of(),
                "clientInfo", Map.of("name", client.getClientName(), "version", client.getClientVersion())));
        negotiatedProtocolVersion = result.path("protocolVersion").asText(PROTOCOL_VERSION);
        sendNotification("notifications/initialized");
        initialized = true;
        log.info("已连接 MCP Server: {}（协商协议版本: {}）", client.getUrl(), negotiatedProtocolVersion);
    }

    private void invalidateSession() {
        this.sessionId = null;
        this.initialized = false;
    }

    /** 发送 JSON-RPC 请求并返回 result 节点；HTTP 404/410 视为会话过期 */
    private JsonNode request(String method, Map<String, Object> params) {
        long id = idSeq.incrementAndGet();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.put("params", params == null ? Map.of() : params);

        HttpResponse<String> resp = post(write(body));
        if (isSessionExpired(resp)) {
            throw new McpStreamHttpException.SessionExpiredException();
        }
        if (!isSuccess(resp)) {
            throw new McpStreamHttpException(
                    "MCP Server 返回 HTTP " + resp.statusCode() + ": " + extractErrorMessage(resp.body()));
        }
        return extractResult(parseJsonRpc(resp, id));
    }

    /** 发送 JSON-RPC 通知（无 id、无响应体），任意 2xx 视为成功 */
    private void sendNotification(String method) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jsonrpc", "2.0");
        body.put("method", method);

        HttpResponse<String> resp = post(write(body));
        if (isSessionExpired(resp)) {
            throw new McpStreamHttpException.SessionExpiredException();
        }
        if (!isSuccess(resp)) {
            throw new McpStreamHttpException(
                    "MCP 通知 [" + method + "] 返回 HTTP " + resp.statusCode() + ": " + extractErrorMessage(resp.body()));
        }
    }

    /** 发送 POST 并统一捕获会话头、异常包装；响应头名大小写不敏感 */
    private HttpResponse<String> post(String jsonBody) {
        HttpRequest httpRequest = buildRequest(jsonBody);
        try {
            HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            resp.headers().firstValue(SESSION_HEADER)
                    .filter(StringUtils::hasText)
                    .ifPresent(sid -> this.sessionId = sid);
            return resp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpStreamHttpException("MCP 请求被中断: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new McpStreamHttpException("MCP 请求失败: " + e.getMessage(), e);
        }
    }

    private HttpRequest buildRequest(String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(client.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT_HEADER)
                .header("MCP-Protocol-Version", negotiatedProtocolVersion)
                .timeout(Duration.ofSeconds(client.getTimeoutS()))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        String sid = sessionId;
        if (StringUtils.hasText(sid)) {
            builder.header(SESSION_HEADER, sid);
        }
        String token = tokenManager.getAccessToken();
        if (StringUtils.hasText(token)) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder.build();
    }

    /** 兼容 application/json 与 text/event-stream 两种响应体，返回匹配请求 id 的 JSON-RPC 信封 */
    private JsonNode parseJsonRpc(HttpResponse<String> resp, long requestId) {
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new McpStreamHttpException("MCP Server 返回空响应体");
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (contentType.startsWith("text/event-stream")) {
            for (JsonNode node : parseSseEvents(body)) {
                if (node == null || !node.isObject()) {
                    continue;
                }
                JsonNode idNode = node.get("id");
                if (idNode != null && idNode.canConvertToLong() && idNode.asLong() == requestId) {
                    return node;
                }
                // 兜底：错误事件可能不带 id，取首个含 result/error 的事件
                if (node.has("result") || node.has("error")) {
                    return node;
                }
            }
            throw new McpStreamHttpException("SSE 流中未收到 id=" + requestId + " 的响应");
        }
        try {
            return objectMapper.readTree(body);
        } catch (JsonProcessingException e) {
            throw new McpStreamHttpException("响应体解析失败: " + e.getMessage(), e);
        }
    }

    /** 解析 SSE 文本为独立 JSON 事件；忽略注释/event/id/retry 行与 keep-alive 非 JSON 事件 */
    private List<JsonNode> parseSseEvents(String body) {
        List<JsonNode> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\\r\\n|\\r|\\n")) {
            if (line.isEmpty()) {
                flushEvent(data, events);
            } else if (line.startsWith("data:")) {
                String payload = line.substring(5);
                if (payload.startsWith(" ")) {
                    payload = payload.substring(1);
                }
                if (!data.isEmpty()) {
                    data.append("\n");
                }
                data.append(payload);
            }
        }
        flushEvent(data, events);
        return events;
    }

    private void flushEvent(StringBuilder data, List<JsonNode> events) {
        if (data.isEmpty()) {
            return;
        }
        try {
            events.add(objectMapper.readTree(data.toString()));
        } catch (JsonProcessingException ignored) {
            // keep-alive 等非 JSON 事件直接丢弃
        }
        data.setLength(0);
    }

    /** 从 JSON-RPC 信封提取 result；含 error 对象时抛 McpRpcException */
    private JsonNode extractResult(JsonNode envelope) {
        if (!envelope.isObject()) {
            throw new McpStreamHttpException("MCP 响应格式错误，期望 JSON-RPC 对象");
        }
        if (envelope.has("error")) {
            JsonNode err = envelope.get("error");
            int code = err.path("code").asInt(-32603);
            String message = err.path("message").asText("未知错误");
            JsonNode data = err.has("data") && !err.get("data").isNull() ? err.get("data") : null;
            throw new McpStreamHttpException.McpRpcException(code, message, data);
        }
        if (envelope.has("result")) {
            return envelope.get("result");
        }
        throw new McpStreamHttpException("MCP 响应既无 result 也无 error");
    }

    private boolean isSessionExpired(HttpResponse<String> resp) {
        return resp.statusCode() == HttpStatus.NOT_FOUND.value()
                || resp.statusCode() == HttpStatus.GONE.value();
    }

    private boolean isSuccess(HttpResponse<String> resp) {
        return resp.statusCode() >= 200 && resp.statusCode() < 300;
    }

    /** 从错误响应体提取可读信息：优先 error.message，其次顶层 message，否则截断原文 */
    private String extractErrorMessage(String body) {
        if (!StringUtils.hasText(body)) {
            return "(无响应体)";
        }
        try {
            JsonNode node = objectMapper.readTree(body);
            JsonNode err = node.path("error");
            if (err.isObject() && err.has("message")) {
                return err.get("message").asText();
            }
            if (node.has("message")) {
                return node.get("message").asText();
            }
        } catch (JsonProcessingException ignored) {
            // 非 JSON 响应体，走下方原文截断
        }
        String text = body.trim();
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new McpStreamHttpException("请求体序列化失败: " + e.getMessage(), e);
        }
    }
}
