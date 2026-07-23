package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        defaultImpl = AnthropicTextDelta.class
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = AnthropicTextDelta.class, name = "text_delta"),
        @JsonSubTypes.Type(value = AnthropicInputJsonDelta.class, name = "input_json_delta"),
        @JsonSubTypes.Type(value = AnthropicThinkingDelta.class, name = "thinking_delta"),
        @JsonSubTypes.Type(value = AnthropicSignatureDelta.class, name = "signature_delta")
})
public interface AnthropicDelta {
}
