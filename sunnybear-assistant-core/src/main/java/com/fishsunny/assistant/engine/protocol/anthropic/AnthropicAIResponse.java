package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.message.content.AnthropicContentBlock;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicAIResponse implements AIResponse {

    private String id;

    private String type;

    private String role;

    private String model;

    private List<AnthropicContentBlock> content = new ArrayList<>();

    private String stop_reason;

    private String stop_sequence;

    private AnthropicUsage usage;

    public AnthropicAIResponse() {
    }
}
