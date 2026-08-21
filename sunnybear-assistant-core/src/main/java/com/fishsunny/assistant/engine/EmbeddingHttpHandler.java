package com.fishsunny.assistant.engine;

/*
 * @Usage Embedding向量生成服务，支持OpenAI兼容的Embedding API
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.EmbeddingAPI;
import com.fishsunny.assistant.engine.protocol.EmbeddingRequest;
import com.fishsunny.assistant.engine.protocol.EmbeddingResponse;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

@Component
public class EmbeddingHttpHandler {

    private static final Long TIME_OUT_SECONDS = 30L;

    private static final Logger log = LoggerFactory.getLogger(EmbeddingHttpHandler.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(TIME_OUT_SECONDS);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public EmbeddingHttpHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(TIMEOUT)
                .build();
    }

    /**
     * 批量文本向量化
     */
    public EmbeddingResponse embed(EmbeddingRequest request, Class<? extends EmbeddingResponse> responseCls, EmbeddingAPI embeddingAPI, @Nullable Function<EmbeddingAPI, Map.Entry<String, String>> tokenBuilder) {
        if (!StringUtils.hasText(embeddingAPI.getModel())) {
            log.warn("Embedding 模型未指定");
            return null;
        }
        if (!StringUtils.hasText(embeddingAPI.getUrl())) {
            log.warn("Embedding url未指定");
            return null;
        }
        if (!StringUtils.hasText(embeddingAPI.getApiKey())) {
            log.warn("Embedding apiKey未指定");
            return null;
        }

        try {
            String requestBody = objectMapper.writeValueAsString(request);

            HttpRequest.Builder httpBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(embeddingAPI.getUrl()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .timeout(TIMEOUT);
            if (tokenBuilder == null) {
                httpBuilder.headers("Authorization", "Bearer " + embeddingAPI.getApiKey());
            } else {
                Map.Entry<String, String> token = tokenBuilder.apply(embeddingAPI);
                httpBuilder.headers(token.getKey(), token.getValue());
            }
            HttpRequest httpRequest = httpBuilder.build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (httpResponse.statusCode() != 200) {
                log.error("Embedding 请求失败: status={}, body={}", httpResponse.statusCode(), httpResponse.body());
                return null;
            }

            return objectMapper.readValue(httpResponse.body(), responseCls);

        } catch (Exception e) {
            log.error("Embedding调用异常: {}", e.getMessage(), e);
            return null;
        }
    }
}
