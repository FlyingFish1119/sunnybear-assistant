package com.fishsunny.assistant.engine.tool.instance.net.search;

/*
 * @Usage Serper Google 搜索引擎实现
 *
 * 通过 Serper API（https://google.serper.dev/search）进行搜索。
 * 请求参数：q, num（size 映射为 num），scope 当前忽略（Serper 仅支持网页搜索）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/6
 */

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

public class SerperSearchEngine implements SearchEngine {

    public static final String ENGINE_NAME = "serper";

    private static final String API_URL = "https://google.serper.dev/search";
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final int HTTP_OK = 200;

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SerperSearchEngine(String apiKey, ObjectMapper objectMapper, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.objectMapper = objectMapper;
        this.httpClient = httpClient;
    }

    @Override
    public String getName() {
        return ENGINE_NAME;
    }

    @Override
    public String search(String query, int size, String scope) throws Exception {
        // Serper 参数适配：size → num
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", query);
        body.put("num", size);

        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("X-API-KEY", apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != HTTP_OK) {
            throw new RuntimeException("Serper 搜索引擎返回错误状态码: " + response.statusCode());
        }

        return response.body();
    }
}
