package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type"
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnthropicTextContent.class, name = "text"),
        @JsonSubTypes.Type(value = AnthropicImageContent.class, name = "image"),
        @JsonSubTypes.Type(value = AnthropicToolUseContent.class, name = "tool_use"),
        @JsonSubTypes.Type(value = AnthropicToolResultContent.class, name = "tool_result"),
        @JsonSubTypes.Type(value = AnthropicThinkingContent.class, name = "thinking")
})
public abstract class AnthropicContentBlock {
}
