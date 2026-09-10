package com.fishsunny.assistant.engine.proxy;


import com.fishsunny.assistant.engine.tool.ToolExecutor;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * ToolRequestProxyMessage
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:20
 */
@Data
public class ToolRequestProxyMessage {

    private List<ToolExecutor.ToolRequest> toolRequests;

    private Map<String, Object> context;
}
