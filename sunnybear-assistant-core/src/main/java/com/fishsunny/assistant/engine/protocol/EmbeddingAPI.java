package com.fishsunny.assistant.engine.protocol;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3 06:17
 */


public interface EmbeddingAPI {
    String getUrl();
    String getApiKey();
    String getModel();
    EmbeddingAPI setUrl(String url);
    EmbeddingAPI setApiKey(String apiKey);
    EmbeddingAPI setModel(String model);
}
