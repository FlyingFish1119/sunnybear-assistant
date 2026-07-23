package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * Stream content block used in content_block_start events.
 * Uses existing content block types polymorphically.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicStreamContentBlock {

    private String type;

    // text block fields
    private String text;

    // tool_use block fields
    private String id;
    private String name;
    private Object input;

    // thinking block fields
    private String thinking;
    private String signature;

    // image block fields
    private Object source;

    public AnthropicStreamContentBlock() {
    }
}
