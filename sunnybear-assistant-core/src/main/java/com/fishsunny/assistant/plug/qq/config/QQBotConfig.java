package com.fishsunny.assistant.plug.qq.config;

/*
 * @Usage QQ Bot 配置
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/11 15:45
 */

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

@Configuration
public class QQBotConfig {

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
