package com.fishsunny.assistant.engine.protocol.embedding;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3 05:41
 */

import com.fishsunny.assistant.engine.protocol.EmbeddingAPI;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardEmbeddingAPI implements EmbeddingAPI {
    private String model;
    private String url;
    private String apiKey;

    public StandardEmbeddingAPI() {
    }

    public StandardEmbeddingAPI(String model, String url, String apiKey) {
        this.model = model;
        this.url = url;
        this.apiKey = apiKey;
    }
}
