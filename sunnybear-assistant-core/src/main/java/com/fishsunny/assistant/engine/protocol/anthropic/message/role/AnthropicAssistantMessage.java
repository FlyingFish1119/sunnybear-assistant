package com.fishsunny.assistant.engine.protocol.anthropic.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.anthropic.message.AnthropicMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicContentBlock;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicAssistantMessage implements AnthropicMessage {

    private final String role = "assistant";

    private List<AnthropicContentBlock> content = new ArrayList<>();

    public AnthropicAssistantMessage() {
    }
}
