package com.fishsunny.assistant.engine.config;

/*
 * @Usage 异步聊天线程池配置 —— 将 AI 调用从 WebSocket 消息处理线程剥离，避免阻塞同一连接上的其他逻辑会话
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/30
 */


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;


import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class EngineHttpConfig {

    @Value("${engine.chat.connection-timeout-s:30}")
    private Integer connectionTimeoutS;

    @Value("${engine.chat.http-version:1.1}")
    private String httpVersion;

    @Bean("aiHttpClient")
    public HttpClient aiHttpClient() {
        HttpClient.Version version = switch (httpVersion) {
            case "1.1" -> HttpClient.Version.HTTP_1_1;
            case "2.0" -> HttpClient.Version.HTTP_2;
            default -> throw new IllegalArgumentException("Invalid HTTP version: " + httpVersion);
        };

        return HttpClient.newBuilder()
                .version(version)
                .connectTimeout(Duration.ofSeconds(connectionTimeoutS))
                .build();
    }
}
