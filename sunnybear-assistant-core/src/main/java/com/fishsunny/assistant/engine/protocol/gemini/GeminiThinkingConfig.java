package com.fishsunny.assistant.engine.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 思考配置（Gemini 3.x）。档位 thinkingLevel：minimal / low / medium / high，
 * 其中 minimal、medium 仅 Flash 系列支持，Pro 只认 low / high。
 * <p>includeThoughts=true 时响应里才会带 thought 摘要 part（不带则思考照常发生、照常计费，只是不返回摘要）。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiThinkingConfig {

    /** 思考档位 minimal / low / medium / high */
    private String thinkingLevel;

    private Boolean includeThoughts;

    public GeminiThinkingConfig() {
    }

    public static GeminiThinkingConfig level(String thinkingLevel, Boolean includeThoughts) {
        return new GeminiThinkingConfig()
                .setThinkingLevel(thinkingLevel)
                .setIncludeThoughts(includeThoughts);
    }
}
