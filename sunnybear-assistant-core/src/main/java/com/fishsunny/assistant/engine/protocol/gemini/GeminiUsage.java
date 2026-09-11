package com.fishsunny.assistant.engine.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * token 用量。totalTokenCount = prompt + thoughts + candidates，
 * 思考 token 单列在 thoughtsTokenCount（无论是否 includeThoughts 都会计费）。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiUsage {

    private Integer promptTokenCount;

    private Integer cachedContentTokenCount;

    private Integer candidatesTokenCount;

    private Integer thoughtsTokenCount;

    private Integer toolUsePromptTokenCount;

    private Integer totalTokenCount;

    public GeminiUsage() {
    }
}
