package com.fishsunny.assistant.engine.tool.service.background;


import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolResponseHandleChain;
import org.springframework.stereotype.Component;

import javax.tools.Tool;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * BackgroundMessageBus
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/21 13:33
 */
@Component
public class BackgroundToolResponseBus implements ToolResponseHandleChain {

    @Override
    public void handle(List<ToolExecutor.ToolExecuteResponse> batchResponses, Map<String, Object> context) {

    }

    @Override
    public int getOrder() {
        return 0;
    }

    private record ResultMessage(String sessionId, ToolExecutor.ToolExecuteResponse response) {}

    private final Map<String, List<ToolExecutor.ToolExecuteResponse>> responseMap = new ConcurrentHashMap<>();

    public void addResponse(String sessionId, ToolExecutor.ToolExecuteResponse response) {
        responseMap.computeIfAbsent(sessionId, k -> new ArrayList<>()).add(response);
    }
}
