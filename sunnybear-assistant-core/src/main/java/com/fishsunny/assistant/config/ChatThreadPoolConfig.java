package com.fishsunny.assistant.config;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * ChatThreadPoolConfig
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 17:04
 */

@Configuration
public class ChatThreadPoolConfig {
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
}
