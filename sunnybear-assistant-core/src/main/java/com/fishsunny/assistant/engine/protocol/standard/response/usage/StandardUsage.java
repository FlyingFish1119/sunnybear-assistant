package com.fishsunny.assistant.engine.protocol.standard.response.usage;

import com.fishsunny.assistant.engine.protocol.TokenUsage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardUsage {

    private Integer prompt_tokens;

    private Integer completion_tokens;

    private Integer total_tokens;

    private Integer cached_tokens;

    private StandardPromptTokensDetails prompt_tokens_details;

    private StandardCompletionTokensDetails completion_tokens_details;

    public StandardUsage() {
    }

    /** OpenAI 标准 / Text 协议的 usage 转换 */
    public static TokenUsage toTokenUsage(StandardUsage usage) {
        if (usage == null) {
            return null;
        }
        Integer cached = usage.getCached_tokens();
        if (cached == null && usage.getPrompt_tokens_details() != null) {
            cached = usage.getPrompt_tokens_details().getCached_tokens();
        }
        Integer reasoning = usage.getCompletion_tokens_details() == null
                ? null : usage.getCompletion_tokens_details().getReasoning_tokens();
        TokenUsage result = new TokenUsage()
                .setPromptTokens(usage.getPrompt_tokens())
                .setCompletionTokens(usage.getCompletion_tokens())
                .setTotalTokens(usage.getTotal_tokens())
                .setCachedTokens(cached)
                .setReasoningTokens(reasoning);
        return result.isEmpty() ? null : result;
    }
}
