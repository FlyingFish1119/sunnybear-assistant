package com.fishsunny.assistant.engine.jev;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Jev（TypeSafe System One）接入配置。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 09:11
 */
@Data
@Component
@ConfigurationProperties(prefix = "engine.jev")
public class JevProperties {

    /**
     * System One 端点地址。
     */
    private String url;

    /**
     * API Key，配合 Bearer 鉴权头使用；支持 ${ENV} 占位。
     */
    private String apiKey;

    /**
     * 模型标识。
     */
    private String model;

    /**
     * 请求超时秒数。
     */
    private Integer timeoutS = 30;
}
