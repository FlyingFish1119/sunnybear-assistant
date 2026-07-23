package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicTextContent implements AnthropicContentBlock {

    private final String type = "text";

    private String text;

    public AnthropicTextContent() {
    }

    public AnthropicTextContent(String text) {
        this.text = text;
    }
}
