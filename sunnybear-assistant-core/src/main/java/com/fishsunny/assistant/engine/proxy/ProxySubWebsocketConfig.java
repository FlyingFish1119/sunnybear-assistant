package com.fishsunny.assistant.engine.proxy;


import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * ProxySubWebsocketConfig
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:17
 */
@Configuration
public class ProxySubWebsocketConfig implements WebSocketConfigurer {

    private final ProxySubWebsocketHandler proxySubWebsocketHandler;

    public ProxySubWebsocketConfig(ProxySubWebsocketHandler proxySubWebsocketHandler) {
        this.proxySubWebsocketHandler = proxySubWebsocketHandler;
    }
    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(proxySubWebsocketHandler, "/ws/proxy")
                .setAllowedOrigins("*");
    }
}
