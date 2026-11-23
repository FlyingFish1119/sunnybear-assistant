package com.fishsunny.assistant.satellite.config;

import com.fishsunny.assistant.satellite.service.SatelliteBridgeService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/*
 * @Usage 卫星桥接 WebSocket 端点自注册 —— 插件端点由各插件自己的 config 注册，不进核心 config/WebSocketConfig。
 *        握手经 SatelliteHandshakeInterceptor 做 Basic 鉴权（未配置凭据时自动跳过）。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/23
 */
@Configuration
public class SatelliteWebSocketConfig implements WebSocketConfigurer {

    private final SatelliteBridgeService satelliteBridgeService;
    private final SatelliteHandshakeInterceptor satelliteHandshakeInterceptor;

    public SatelliteWebSocketConfig(SatelliteBridgeService satelliteBridgeService,
                                     SatelliteHandshakeInterceptor satelliteHandshakeInterceptor) {
        this.satelliteBridgeService = satelliteBridgeService;
        this.satelliteHandshakeInterceptor = satelliteHandshakeInterceptor;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(satelliteBridgeService, "/satellite-bridge")
                .addInterceptors(satelliteHandshakeInterceptor)
                .setAllowedOrigins("*");
    }
}
