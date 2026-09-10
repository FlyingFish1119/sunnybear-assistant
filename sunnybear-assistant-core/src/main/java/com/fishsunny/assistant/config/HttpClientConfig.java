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

    @Bean
    @Primary
    public HttpClient httpClient() {
        return HttpClient.newHttpClient();
    }
}
