package com.fishsunny.assistant.engine.protocol.embedding;

/*
 * @Usage OpenAI-compatible embedding request
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.EmbeddingRequest;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardEmbeddingRequest implements EmbeddingRequest {

    private String model;

    /**
     * Single string or list of strings to embed
     */
    private Object input;

    /**
     * "float" or "base64", default "float"
     */
    private String encodingFormat = "float";

    public StandardEmbeddingRequest() {
    }

    @Override
    public Object inputs() {
        return input;
    }
}
