package com.fishsunny.assistant.engine.protocol;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3 05:36
 */

public interface EmbeddingResponse {
    Object data();
    Object usage();
}
