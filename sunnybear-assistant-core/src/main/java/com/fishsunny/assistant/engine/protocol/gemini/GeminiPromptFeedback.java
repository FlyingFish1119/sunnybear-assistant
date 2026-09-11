package com.fishsunny.assistant.engine.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 提示词反馈。被安全策略拦截时 HTTP 仍是 200，但 candidates 为空、这里给出 blockReason，
 * 适配器据此抛出可读错误而不是返回一条空回复。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiPromptFeedback {

    private String blockReason;

    private List<Map<String, Object>> safetyRatings;

    public GeminiPromptFeedback() {
    }
}
