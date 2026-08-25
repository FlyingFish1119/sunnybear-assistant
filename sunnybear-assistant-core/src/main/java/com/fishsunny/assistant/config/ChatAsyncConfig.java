package com.fishsunny.assistant.config;

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
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class ChatAsyncConfig {

    @Value("${assistant.chat.async.core-pool-size:4}")
    private int corePoolSize;

    @Value("${assistant.chat.async.max-pool-size:10}")
    private int maxPoolSize;

    @Value("${assistant.chat.async.queue-capacity:100}")
    private int queueCapacity;

    @Value("${assistant.chat.async.thread-name-prefix:chat-async-}")
    private String threadNamePrefix;

    @Bean(name = "chatAsyncExecutor")
    public TaskExecutor chatAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * 共享 HttpClient 实例，供所有 AI 适配器复用。
     * 使用 HTTP/1.1 避免 HTTP/2 连接复用
     */
    @Bean
    public HttpClient sharedHttpClient() {
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }
}
