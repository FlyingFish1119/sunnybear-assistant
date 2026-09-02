package com.fishsunny.assistant.config;


import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * HttpClientConfig
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/2 13:22
 */
@Configuration
public class HttpClientConfig {


    /**
     * 共享 HttpClient 实例，供所有 AI 适配器复用。
     * 使用 HTTP/1.1 避免 HTTP/2 连接复用
     */
    @Bean("aiHttpClient")
    public HttpClient aiHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    @Primary
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }
}
