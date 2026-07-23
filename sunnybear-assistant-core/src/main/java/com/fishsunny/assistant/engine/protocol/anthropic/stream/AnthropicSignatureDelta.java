package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicSignatureDelta implements AnthropicDelta {

    private final String type = "signature_delta";

    private String signature;

    public AnthropicSignatureDelta() {
    }
}
