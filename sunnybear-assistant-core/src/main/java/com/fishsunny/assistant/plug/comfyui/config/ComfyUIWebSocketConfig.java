package com.fishsunny.assistant.plug.comfyui.config;

/*
 * @Usage ComfyUI 桥接 WebSocket 端点自注册 —— 不再注册在核心 config/WebSocketConfig。
 *
 * @Project sunnybear-assistant
 */

import com.fishsunny.assistant.plug.comfyui.service.ComfyUIBridgeService;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class ComfyUIWebSocketConfig implements WebSocketConfigurer {

    private final ComfyUIBridgeService comfyUIBridgeService;

    public ComfyUIWebSocketConfig(ComfyUIBridgeService comfyUIBridgeService) {
        this.comfyUIBridgeService = comfyUIBridgeService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(comfyUIBridgeService, "/comfyui-bridge")
                .setAllowedOrigins("*");
    }
}
