package com.fishsunny.assistant.config;

/*
 * @Usage 异步聊天线程池配置 —— 将 AI 调用从 WebSocket 消息处理线程剥离，避免阻塞同一连接上的其他逻辑会话
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/30
 */

import com.fishsunny.assistant.engine.adapter.AIAdapterProperties;
import com.fishsunny.assistant.engine.adapter.factory.AIAdapterFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.net.http.HttpClient;

@Configuration
public class ChatConfig {

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

    @Bean
    public AIAdapterFactory aiAdapterFactory(AIAdapterProperties properties, @Qualifier("aiHttpClient") HttpClient httpClient) {
        return new AIAdapterFactory(properties, httpClient);
    }
}
