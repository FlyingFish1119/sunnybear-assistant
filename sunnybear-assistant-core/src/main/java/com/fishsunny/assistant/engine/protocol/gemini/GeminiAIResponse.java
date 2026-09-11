package com.fishsunny.assistant.engine.protocol.gemini;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.gemini.message.GeminiCandidate;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * generateContent 的完整响应体。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiAIResponse implements AIResponse {

    private List<GeminiCandidate> candidates = new ArrayList<>();

    private GeminiUsage usageMetadata;

    private GeminiPromptFeedback promptFeedback;

    private String responseId;

    private String modelVersion;

    public GeminiAIResponse setCandidates(List<GeminiCandidate> candidates) {
        this.candidates = candidates == null ? new ArrayList<>() : candidates;
        return this;
    }

    public GeminiAIResponse() {
    }

    /** 首个候选，无候选（如被安全策略拦截）时返回 null */
    public GeminiCandidate firstCandidate() {
        return candidates == null || candidates.isEmpty() ? null : candidates.getFirst();
    }
}
