package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicTextDelta implements AnthropicDelta {

    private final String type = "text_delta";

    private String text;

    public AnthropicTextDelta() {
    }
}
