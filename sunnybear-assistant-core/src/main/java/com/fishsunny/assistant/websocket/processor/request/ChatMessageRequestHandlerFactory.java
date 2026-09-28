package com.fishsunny.assistant.websocket.processor.request;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 20:09
 */

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ChatMessageRequestHandlerFactory {

    private final Map<String, ChatMessageRequestHandler> handlers = new ConcurrentHashMap<>();

    public ChatMessageRequestHandlerFactory(List<ChatMessageRequestHandler> handlers) {
        handlers.forEach(handler -> this.handlers.put(handler.getMode(), handler));
    }

    public ChatMessageRequestHandler getHandler(String mode) {
        ChatMessageRequestHandler handler = handlers.get(mode);
        if (handler == null) {
            throw new IllegalArgumentException("No handler for mode: " + mode);
        }
        return handler;
    }
}
