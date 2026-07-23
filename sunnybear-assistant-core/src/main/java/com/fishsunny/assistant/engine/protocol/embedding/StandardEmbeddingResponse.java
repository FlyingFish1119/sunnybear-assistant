package com.fishsunny.assistant.engine.protocol.embedding;

/*
 * @Usage OpenAI-compatible embedding response
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.EmbeddingResponse;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class StandardEmbeddingResponse implements EmbeddingResponse {

    private String object;

    private List<EmbeddingData> data;

    private String model;

    private EmbeddingUsage usage;

    @Override
    public Object data() {
        return data;
    }

    @Override
    public Object usage() {
        return usage;
    }

    @Data
    @Accessors(chain = true)
    public static class EmbeddingData {
        private String object;
        private List<Float> embedding;
        private Integer index;
    }

    @Data
    @Accessors(chain = true)
    public static class EmbeddingUsage {
        private Integer prompt_tokens;
        private Integer total_tokens;
    }
}
