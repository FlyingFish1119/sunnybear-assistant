package com.fishsunny.assistant.plug.world.config;

/*
 * @Usage 世界模块 WebSocket 端点自注册 —— 世界群聊的 WS 路径由插件自己声明，
 *        不再注册在核心 config/WebSocketConfig（避免核心反向依赖插件）。
 *
 * @Project sunnybear-assistant
 */

import com.fishsunny.assistant.plug.world.websocket.WorldGroupChatSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class WorldWebSocketConfig implements WebSocketConfigurer {

    private final WorldGroupChatSocketHandler worldGroupChatSocketHandler;

    public WorldWebSocketConfig(WorldGroupChatSocketHandler worldGroupChatSocketHandler) {
        this.worldGroupChatSocketHandler = worldGroupChatSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(worldGroupChatSocketHandler, "/plug/world/ws/world-chat")
                .setAllowedOrigins("*");
    }
}
