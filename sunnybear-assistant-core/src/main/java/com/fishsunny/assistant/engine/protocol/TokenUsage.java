package com.fishsunny.assistant.engine.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiUsage;
import com.fishsunny.assistant.engine.protocol.standard.response.usage.StandardUsage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 协议无关的 token 用量。
 *
 * <p>各协议响应里的 usage（OpenAI 标准 / Text / Gemini 等）在各自响应类里转成本类，
 * 主链路只认这一种结构，落库时再展开成 extension 里的 snake_case map。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TokenUsage {

    /** 输入（prompt）token 数 */
    private Integer promptTokens;

    /** 输出（completion）token 数，通常已包含思考 token */
    private Integer completionTokens;

    /** 总 token 数 */
    private Integer totalTokens;

    /** 命中缓存的输入 token 数 */
    private Integer cachedTokens;

    /** 思考 / 推理 token 数 */
    private Integer reasoningTokens;

    public TokenUsage() {
    }

    /**
     * 合并后到的用量帧：非空字段以后到者为准，返回新的用量对象（本对象不动）。
     *
     * <p>流式协议里用量常常分帧给出——比如 Anthropic 的 message_start 给输入与缓存、
     * message_delta 只给最终输出，主链路按帧累积，靠它拼出完整用量。
     */
    public TokenUsage merge(TokenUsage newer) {
        if (newer == null) {
            return this;
        }
        return new TokenUsage()
                .setPromptTokens(newer.promptTokens != null ? newer.promptTokens : promptTokens)
                .setCompletionTokens(newer.completionTokens != null ? newer.completionTokens : completionTokens)
                .setTotalTokens(newer.totalTokens != null ? newer.totalTokens : totalTokens)
                .setCachedTokens(newer.cachedTokens != null ? newer.cachedTokens : cachedTokens)
                .setReasoningTokens(newer.reasoningTokens != null ? newer.reasoningTokens : reasoningTokens);
    }

    /** 是否没有任何有效数字 */
    public boolean isEmpty() {
        return promptTokens == null && completionTokens == null && totalTokens == null
                && cachedTokens == null && reasoningTokens == null;
    }

    /** 展开为 snake_case map（跳过 null），用于写入 extension */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        if (promptTokens != null) {
            map.put("prompt_tokens", promptTokens);
        }
        if (completionTokens != null) {
            map.put("completion_tokens", completionTokens);
        }
        if (totalTokens != null) {
            map.put("total_tokens", totalTokens);
        }
        if (cachedTokens != null) {
            map.put("cached_tokens", cachedTokens);
        }
        if (reasoningTokens != null) {
            map.put("reasoning_tokens", reasoningTokens);
        }
        return map;
    }
}
