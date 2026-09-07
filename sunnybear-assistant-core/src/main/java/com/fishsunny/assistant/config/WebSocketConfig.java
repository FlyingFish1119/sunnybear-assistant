package com.fishsunny.assistant.config;

import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 核心 WebSocket 端点注册。
 * <p>插件的 WS 端点由各插件自己的 config 注册（character / world / android / comfyui），
 * 核心层不反向依赖插件 handler，此处仅保留核心聊天的 {@code /ws/chat} 端点与容器参数。
 * <p>注意：{@code @EnableWebSocket} 保留在此，Spring 会收集容器内所有 {@link WebSocketConfigurer} bean
 * 并逐个调用 {@code registerWebSocketHandlers}，因此插件的 config 无需再写 {@code @EnableWebSocket}。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final ChatWebSocketHandler chatWebSocketHandler;

    public WebSocketConfig(ChatWebSocketHandler chatWebSocketHandler) {
        this.chatWebSocketHandler = chatWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(chatWebSocketHandler, "/ws/chat")
                .setAllowedOrigins("*");
    }

    /**
     * 配置 WebSocket 容器参数
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxTextMessageBufferSize(50 * 1024 * 1024);
        container.setMaxBinaryMessageBufferSize(50 * 1024 * 1024);
        // 会话空闲超时 30 分钟
        container.setMaxSessionIdleTimeout(15 * 60 * 1000L);
        return container;
    }
}
