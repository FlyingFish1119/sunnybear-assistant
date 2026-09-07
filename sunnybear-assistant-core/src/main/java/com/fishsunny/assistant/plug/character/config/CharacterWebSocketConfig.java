package com.fishsunny.assistant.plug.character.config;

/*
 * @Usage 角色模块 WebSocket 端点自注册 —— 角色聊天的 WS 路径由插件自己声明，
 *        不再注册在核心 config/WebSocketConfig（避免核心反向依赖插件）。
 *
 * @Project sunnybear-assistant
 */

import com.fishsunny.assistant.plug.character.websocket.CharacterChatSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
public class CharacterWebSocketConfig implements WebSocketConfigurer {

    private final CharacterChatSocketHandler characterChatSocketHandler;

    public CharacterWebSocketConfig(CharacterChatSocketHandler characterChatSocketHandler) {
        this.characterChatSocketHandler = characterChatSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(characterChatSocketHandler, "/ws/character-chat")
                .setAllowedOrigins("*");
    }
}
