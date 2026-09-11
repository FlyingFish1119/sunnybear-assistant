package com.fishsunny.assistant.engine.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 采样与输出配置。字段名是 camelCase（topP / maxOutputTokens），与 OpenAI 风格的
 * top_p / max_tokens 不同，转换在适配器里做。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiGenerationConfig {

    private Double temperature;

    private Double topP;

    private Integer topK;

    /** 输出 token 上限，思考 token 也算在里面（思考多了正文可用额度就少） */
    private Integer maxOutputTokens;

    private Integer candidateCount;

    private List<String> stopSequences;

    /** 输出格式，如 application/json（对应项目里的 response_format: json_object） */
    private String responseMimeType;

    private GeminiThinkingConfig thinkingConfig;

    public GeminiGenerationConfig() {
    }
}
