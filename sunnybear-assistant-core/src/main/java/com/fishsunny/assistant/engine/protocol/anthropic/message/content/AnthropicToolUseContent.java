package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicToolUseContent extends AnthropicContentBlock {

    private final String type = "tool_use";

    private String id;

    private String name;

    private Map<String, Object> input;

    public AnthropicToolUseContent() {
    }
}
