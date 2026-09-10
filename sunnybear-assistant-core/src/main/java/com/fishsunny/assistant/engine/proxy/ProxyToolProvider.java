package com.fishsunny.assistant.engine.proxy;

import com.fishsunny.assistant.engine.tool.ToolExecutor;

/**
 * ProxyToolProvider
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:21
 */
public interface ProxyToolProvider {

    ToolExecutor.ToolProvider getToolProvider();
}
