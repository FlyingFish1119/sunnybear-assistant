package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Responses API（{@code POST /v1/responses}）的 token 用量。
 *
 * <p>字段名与 Chat Completions 不同：输入 / 输出分别叫 {@code input_tokens} / {@code output_tokens}，
 * 缓存与思考明细分别下钻到 {@code *_tokens_details}：
 * <pre>
 * "usage": {
 *   "input_tokens": 31,
 *   "input_tokens_details": { "cached_tokens": 0 },
 *   "output_tokens": 18,
 *   "output_tokens_details": { "reasoning_tokens": 0 },
 *   "total_tokens": 49
 * }
 * </pre>
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesUsage {

    private Integer input_tokens;

    private Integer output_tokens;

    private Integer total_tokens;

    private ResponsesInputTokensDetails input_tokens_details;

    private ResponsesOutputTokensDetails output_tokens_details;

    public ResponsesUsage() {
    }

    /** 转成协议无关的用量；无有效数字时返回 null（与其它协议同口径） */
    public static TokenUsage toTokenUsage(ResponsesUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer cached = usage.getInput_tokens_details() == null
                ? null : usage.getInput_tokens_details().getCached_tokens();
        Integer reasoning = usage.getOutput_tokens_details() == null
                ? null : usage.getOutput_tokens_details().getReasoning_tokens();
        TokenUsage result = new TokenUsage()
                .setPromptTokens(usage.getInput_tokens())
                .setCompletionTokens(usage.getOutput_tokens())
                .setTotalTokens(usage.getTotal_tokens())
                .setCachedTokens(cached)
                .setReasoningTokens(reasoning);
        return result.isEmpty() ? null : result;
    }
}
