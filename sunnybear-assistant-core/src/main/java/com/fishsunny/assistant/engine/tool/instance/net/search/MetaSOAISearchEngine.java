package com.fishsunny.assistant.engine.tool.instance.net.search;

/*
 * @Usage MetaSOAI 搜索引擎实现
 *
 * 通过 MetaSo API（https://metaso.cn/api/v1/search）进行搜索。
 * 请求参数：q, size, scope
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

public class MetaSOAISearchEngine implements SearchEngine {

    public static final String ENGINE_NAME = "metaso";

    private static final String API_URL = "https://metaso.cn/api/v1/search";
    private static final int REQUEST_TIMEOUT_SECONDS = 30;
    private static final int HTTP_OK = 200;

    private final String apiKey;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public MetaSOAISearchEngine(String apiKey, ObjectMapper objectMapper, HttpClient httpClient) {
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
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("q", query);
        body.put("size", size);
        body.put("scope", scope != null ? scope : "webpage");

        String requestBody = objectMapper.writeValueAsString(body);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(REQUEST_TIMEOUT_SECONDS))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        if (response.statusCode() != HTTP_OK) {
            throw new RuntimeException("MetaSOAI 搜索引擎返回错误状态码: " + response.statusCode());
        }

        return response.body();
    }
}
