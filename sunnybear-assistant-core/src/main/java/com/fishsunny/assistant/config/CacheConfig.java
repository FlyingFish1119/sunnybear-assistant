package com.fishsunny.assistant.config;

/*
 * @Usage 本地缓存配置 —— 提供 Caffeine Cache 实例，通过依赖注入使用，缓存逻辑由各 Service 自行控制
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22
 */

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
public class CacheConfig {

    @Value("${assistant.cache.max-size:500}")
    private int maxSize;

    @Value("${assistant.cache.expire-minutes:10}")
    private int expireMinutes;

    @Bean
    public Cache<String, Object> defaultCache() {
        return Caffeine.newBuilder()
                .maximumSize(maxSize)
                .expireAfterWrite(expireMinutes, TimeUnit.MINUTES)
                .build();
    }
}
