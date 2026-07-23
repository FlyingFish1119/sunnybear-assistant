package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicThinking {

    private String type;

    private Integer budget_tokens;

    public AnthropicThinking() {
    }

    public AnthropicThinking(String type, Integer budgetTokens) {
        this.type = type;
        this.budget_tokens = budgetTokens;
    }

    /**
     * Create an enabled thinking config with the given budget.
     */
    public static AnthropicThinking enabled(int budgetTokens) {
        return new AnthropicThinking("enabled", budgetTokens);
    }

    /**
     * Create a disabled thinking config.
     */
    public static AnthropicThinking disabled() {
        return new AnthropicThinking("disabled", null);
    }
}
