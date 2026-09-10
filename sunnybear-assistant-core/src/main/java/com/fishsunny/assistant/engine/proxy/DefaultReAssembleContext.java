package com.fishsunny.assistant.engine.proxy;


import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * DefaultProxyToolProvider
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:22
 */
@ConditionalOnMissingBean
@Component
public class DefaultReAssembleContext implements ReAssembleContext {

    @Override
    public Map<String, Object> reAssemble(Map<String, Object> context) {
        return context;
    }
}
