package com.fishsunny.assistant.plug.android.config;

/*
 * @Usage Android 设备桥接 WebSocket 端点自注册 —— 不再注册在核心 config/WebSocketConfig。
 *
 * @Project sunnybear-assistant
 */

import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class AndroidWebSocketConfig implements WebSocketConfigurer {

    private final AndroidBridgeService androidBridgeService;

    public AndroidWebSocketConfig(AndroidBridgeService androidBridgeService) {
        this.androidBridgeService = androidBridgeService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(androidBridgeService, "/android-bridge")
                .setAllowedOrigins("*");
    }
}
