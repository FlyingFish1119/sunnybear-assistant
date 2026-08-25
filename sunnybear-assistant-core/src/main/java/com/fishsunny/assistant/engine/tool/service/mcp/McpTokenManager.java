package com.fishsunny.assistant.engine.tool.service.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class McpTokenManager {

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final McpProperties.Client client;

    private final AtomicBoolean refreshing = new AtomicBoolean(false);

    private String accessToken;

    private String refreshToken;

    private long lastFreshTime;

    public McpTokenManager(McpProperties.Client client, HttpClient httpClient, ObjectMapper objectMapper) {
        this.client = client;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    /** 供 transport 的 customizeRequest 调用 */
    public synchronized String getAccessToken() {
        if (!StringUtils.hasText(client.getTokenUrl())) {
            return "";
        }
        if (lastFreshTime + 60000L < System.currentTimeMillis()) {
            doRefresh();
        }
        return accessToken;
    }

    private void doRefresh() {
        try {
            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + URLEncoder.encode(refreshToken, StandardCharsets.UTF_8)
                    + "&client_id=" + URLEncoder.encode(client.getUsername(), StandardCharsets.UTF_8)
                    + "&client_secret=" + URLEncoder.encode(client.getPassword(), StandardCharsets.UTF_8);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(client.getTokenUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != HttpStatus.OK.value()) {
                throw new IllegalStateException("刷新 token 失败: HTTP " + resp.statusCode());
            }

            Token node = objectMapper.readValue(resp.body(), Token.class);
            this.accessToken = node.accessToken();
            if (StringUtils.hasText(node.refreshToken())) {
                this.refreshToken = node.refreshToken();
            }
            lastFreshTime = System.currentTimeMillis();
        } catch (Exception e) {
            log.warn("MCP token 刷新异常: {}", e.getMessage());
        }
    }

    record Token(String accessToken, String refreshToken) {}
}