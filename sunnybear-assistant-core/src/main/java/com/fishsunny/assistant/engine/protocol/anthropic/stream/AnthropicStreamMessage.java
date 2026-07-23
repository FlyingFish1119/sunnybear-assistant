package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicUsage;
import lombok.Data;

/**
 * The "message" sub-object in Anthropic message_start SSE events.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicStreamMessage {

    private String id;

    private String type;

    private String role;

    private String model;

    private AnthropicUsage usage;

    public AnthropicStreamMessage() {
    }
}
