package com.fishsunny.assistant.engine.jev;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.jev.request.JevRequest;
import com.fishsunny.assistant.engine.jev.response.JevResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/22 10:40
 */
@Slf4j
@Component
public class JevClient {

    private final JevProperties properties;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public JevClient(JevProperties properties,
                     @Qualifier("aiHttpClient") HttpClient httpClient,
                     ObjectMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public JevResponseMapper send(JevRequest request) throws Exception {
        request.setModel(properties.getModel());
        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(properties.getUrl()))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + properties.getApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(request)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            log.error("JevClient send error, response body: {}", response.body());
            throw new JevException("JevClient send error, response body: " + response.body() + ", status code: " + response.statusCode());
        }
        return new JevResponseMapper(objectMapper.readValue(response.body(), JevResponse.class));
    }
}
