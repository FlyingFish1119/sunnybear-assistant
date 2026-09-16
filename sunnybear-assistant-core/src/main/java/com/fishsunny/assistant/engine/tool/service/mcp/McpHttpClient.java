package com.fishsunny.assistant.engine.tool.service.mcp;

/*
 * @Usage MCP Streamable HTTP 传输：POST JSON-RPC 信封，响应体兼容 application/json 与 text/event-stream。
 *        响应体一次性读完再解析，不做增量流式处理；Mcp-Session-Id 由服务端在响应头下发、客户端回传。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/16
 */

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
import java.util.List;

@Slf4j
public class McpHttpClient extends AbstractMcpClient {

    /** 告知服务端可同时接受 JSON 与 SSE 两种响应体 */
    private static final String ACCEPT_HEADER = "application/json, text/event-stream";

    /** 会话标识头：握手后从响应头捕获并回传 */
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private final HttpClient httpClient;

    /** 握手后非空；随每次响应头刷新 */
    private volatile String sessionId;

    public McpHttpClient(McpProperties.Client client, HttpClient httpClient, ObjectMapper objectMapper) {
        super(client, objectMapper);
        this.httpClient = httpClient;
    }

    @Override
    protected void open() {
        // HTTP 无连接状态，握手由骨架通过 exchange 完成
    }

    @Override
    protected JsonNode exchange(JsonNode envelope) {
        long id = envelope.path("id").asLong();
        HttpResponse<String> resp = post(envelope);
        if (isSessionExpired(resp)) {
            throw new McpException.SessionExpiredException();
        }
        if (!isSuccess(resp)) {
            throw new McpException("MCP Server 返回 HTTP " + resp.statusCode()
                    + ": " + extractErrorMessage(resp.body()));
        }
        return parseJsonRpc(resp, id);
    }

    @Override
    protected void send(JsonNode envelope) {
        HttpResponse<String> resp = post(envelope);
        if (isSessionExpired(resp)) {
            throw new McpException.SessionExpiredException();
        }
        if (!isSuccess(resp)) {
            throw new McpException("MCP 通知 [" + envelope.path("method").asText() + "] 返回 HTTP "
                    + resp.statusCode() + ": " + extractErrorMessage(resp.body()));
        }
    }

    @Override
    protected void closeTransport() {
        // java.net.http.HttpClient 无需显式释放
    }

    @Override
    protected String transport() {
        return "http";
    }

    @Override
    protected void reset() {
        this.sessionId = null;
        super.reset();
    }

    /** 发送 POST 并统一捕获会话头、异常包装；响应头名大小写不敏感 */
    private HttpResponse<String> post(JsonNode envelope) {
        HttpRequest httpRequest = buildRequest(envelope);
        try {
            HttpResponse<String> resp = httpClient.send(httpRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            resp.headers().firstValue(SESSION_HEADER)
                    .filter(StringUtils::hasText)
                    .ifPresent(sid -> this.sessionId = sid);
            return resp;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new McpException("MCP 请求被中断: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new McpException("MCP 请求失败: " + e.getMessage(), e);
        }
    }

    private HttpRequest buildRequest(JsonNode envelope) {
        if (!StringUtils.hasText(client.getUrl())) {
            throw new McpException("MCP Server [" + serverName() + "] transport=http 但未配置 url");
        }
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(client.getUrl()))
                .header("Content-Type", "application/json")
                .header("Accept", ACCEPT_HEADER)
                .header("MCP-Protocol-Version", negotiatedProtocolVersion)
                .timeout(Duration.ofSeconds(client.getTimeoutS()))
                .POST(HttpRequest.BodyPublishers.ofString(envelope.toString(), StandardCharsets.UTF_8));

        String sid = sessionId;
        if (StringUtils.hasText(sid)) {
            builder.header(SESSION_HEADER, sid);
        }
        return builder.build();
    }

    /** 兼容 application/json 与 text/event-stream 两种响应体，返回匹配请求 id 的 JSON-RPC 信封 */
    private JsonNode parseJsonRpc(HttpResponse<String> resp, long requestId) {
        String body = resp.body();
        if (body == null || body.isBlank()) {
            throw new McpException("MCP Server 返回空响应体");
        }
        String contentType = resp.headers().firstValue("Content-Type").orElse("").toLowerCase();
        if (contentType.startsWith("text/event-stream")) {
            for (JsonNode node : parseSseEvents(body)) {
                JsonNode idNode = node.get("id");
                if (idNode != null && idNode.canConvertToLong() && idNode.asLong() == requestId) {
                    return node;
                }
                // 兜底：错误事件可能不带 id，取首个含 result/error 的事件
                if (node.has("result") || node.has("error")) {
                    return node;
                }
            }
            throw new McpException("SSE 流中未收到 id=" + requestId + " 的响应");
        }
        return readTree(body);
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
            JsonNode node = objectMapper.readTree(data.toString());
            if (node != null && node.isObject()) {
                events.add(node);
            }
        } catch (Exception ignored) {
            // keep-alive 等非 JSON 事件直接丢弃
        }
        data.setLength(0);
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
        } catch (Exception ignored) {
            // 非 JSON 响应体，走下方原文截断
        }
        String text = body.trim();
        return text.length() > 200 ? text.substring(0, 200) + "..." : text;
    }
}
