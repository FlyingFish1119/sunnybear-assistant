package com.fishsunny.assistant.engine.proxy;


import com.fishsunny.assistant.engine.tool.ToolExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

/**
 * FastFailProxyToolProvider
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:21
 */
@ConditionalOnMissingBean
@Component
public class DefaultProxyToolProvider implements ProxyToolProvider {

    @Override
    public ToolExecutor.ToolProvider getToolProvider() {
        return new ToolExecutor.ToolProvider(null, null);
    }
}
