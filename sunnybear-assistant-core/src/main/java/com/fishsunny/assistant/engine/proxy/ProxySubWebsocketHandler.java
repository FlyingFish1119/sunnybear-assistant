package com.fishsunny.assistant.engine.proxy;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import lombok.RequiredArgsConstructor;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.List;
import java.util.Map;

/**
 * PAD
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:14
 */
@Component
@RequiredArgsConstructor
public class ProxySubWebsocketHandler extends TextWebSocketHandler {

    private final ProxyToolProvider proxyToolProvider;
    private final ReAssembleContext reAssembleContext;
    private final ObjectMapper objectMapper;
    private final ToolExecutor toolExecutor;

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) throws Exception {
        String payload = message.getPayload();

        ToolRequestProxyMessage proxyMessage = objectMapper.readValue(payload, ToolRequestProxyMessage.class);

        Map<String, Object> context = reAssembleContext.reAssemble(proxyMessage.getContext());
        List<ToolExecutor.ToolExecuteResponse> toolExecuteResponses = toolExecutor.execute(proxyMessage.getToolRequests(), context, proxyToolProvider.getToolProvider());

        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(toolExecuteResponses)));
    }
}
