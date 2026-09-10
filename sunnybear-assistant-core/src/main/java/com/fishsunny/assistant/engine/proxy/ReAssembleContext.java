package com.fishsunny.assistant.engine.proxy;

import java.util.Map;

/**
 * ReAssembleContext
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/10 18:19
 */
public interface ReAssembleContext {

    Map<String, Object> reAssemble(Map<String, Object> context);
}
