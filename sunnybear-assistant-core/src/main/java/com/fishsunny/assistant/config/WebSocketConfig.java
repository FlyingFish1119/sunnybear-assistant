package com.fishsunny.assistant.config;

import com.fishsunny.assistant.plug.android.service.AndroidBridgeService;
import com.fishsunny.assistant.plug.character.websocket.CharacterChatSocketHandler;
import com.fishsunny.assistant.plug.comfyui.service.ComfyUIBridgeService;
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;
    private final CharacterChatSocketHandler characterChatSocketHandler;
    private final AndroidBridgeService androidBridgeService;
    private final ComfyUIBridgeService comfyUIBridgeService;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler,
                           CharacterChatSocketHandler characterChatSocketHandler,
                           AndroidBridgeService androidBridgeService,
                           ComfyUIBridgeService comfyUIBridgeService) {
        this.chatWebSocketHandler = chatWebSocketHandler;
        this.characterChatSocketHandler = characterChatSocketHandler;
        this.androidBridgeService = androidBridgeService;
        this.comfyUIBridgeService = comfyUIBridgeService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*");
        registry.addHandler(characterChatSocketHandler, "/ws/character-chat")
                .setAllowedOrigins("*");
        registry.addHandler(androidBridgeService, "/android-bridge")
                .setAllowedOrigins("*");
        registry.addHandler(comfyUIBridgeService, "/comfyui-bridge")
                .setAllowedOrigins("*");
    }

    /**
     * 配置 WebSocket 容器参数
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        // 消息缓冲区 100MB，支持含 base64 图片/文件的上传消息 + 较长 AI 回复
        container.setMaxTextMessageBufferSize(20 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(20 * 1024 * 1024);
        // 会话空闲超时 30 分钟
        container.setMaxSessionIdleTimeout(30 * 60 * 1000L);
        return container;
    }
}