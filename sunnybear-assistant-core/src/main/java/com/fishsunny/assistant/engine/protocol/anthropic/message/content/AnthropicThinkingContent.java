package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicThinkingContent implements AnthropicContentBlock {

    private final String type = "thinking";

    private String thinking;

    private String signature;

    public AnthropicThinkingContent() {
    }
}
