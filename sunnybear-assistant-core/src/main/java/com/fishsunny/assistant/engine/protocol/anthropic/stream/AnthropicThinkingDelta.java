package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicThinkingDelta implements AnthropicDelta {

    private final String type = "thinking_delta";

    private String thinking;

    public AnthropicThinkingDelta() {
    }
}
