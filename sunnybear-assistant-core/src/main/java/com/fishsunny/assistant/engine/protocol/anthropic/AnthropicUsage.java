package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicUsage {

    private Integer input_tokens;

    private Integer output_tokens;

    /** 本轮写入缓存的输入 token 数（缓存未开启时上游给 0） */
    private Integer cache_creation_input_tokens;

    /** 本轮命中缓存读取的输入 token 数 */
    private Integer cache_read_input_tokens;

    public AnthropicUsage() {
    }

    /**
     * 转成协议无关的用量；无有效数字时返回 null（与其它协议同口径）。
     *
     * <p>映射关系：{@code input_tokens} → promptTokens、{@code output_tokens} → completionTokens
     * （Anthropic 的输出数已含思考 token）、{@code cache_read_input_tokens} → cachedTokens。
     * 上游不单独给「思考 token」与「总 token」，这两个字段留空，不替上游推算。
     */
    public static TokenUsage toTokenUsage(AnthropicUsage usage) {
        if (usage == null) {
            return null;
        }
        TokenUsage result = new TokenUsage()
                .setPromptTokens(usage.getInput_tokens())
                .setCompletionTokens(usage.getOutput_tokens())
                .setCachedTokens(usage.getCache_read_input_tokens());
        return result.isEmpty() ? null : result;
    }
}
