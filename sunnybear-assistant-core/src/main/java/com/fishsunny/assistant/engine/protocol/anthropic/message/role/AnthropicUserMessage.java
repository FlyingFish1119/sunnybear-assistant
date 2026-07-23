package com.fishsunny.assistant.engine.protocol.anthropic.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.anthropic.message.AnthropicMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicContentBlock;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicTextContent;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicUserMessage implements AnthropicMessage {

    private final String role = "user";

    private List<AnthropicContentBlock> content = new ArrayList<>();

    public AnthropicUserMessage() {
    }

    public AnthropicUserMessage(String text) {
        this.content = List.of(new AnthropicTextContent(text));
    }
}
