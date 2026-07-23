package com.fishsunny.assistant.engine.protocol.anthropic.message;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fishsunny.assistant.engine.protocol.anthropic.message.role.AnthropicAssistantMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.message.role.AnthropicUserMessage;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "role",
        defaultImpl = AnthropicUserMessage.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnthropicUserMessage.class, name = "user"),
        @JsonSubTypes.Type(value = AnthropicAssistantMessage.class, name = "assistant")
})
public interface AnthropicMessage {
}
