package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicInputJsonDelta implements AnthropicDelta {

    private final String type = "input_json_delta";

    private String partial_json;

    public AnthropicInputJsonDelta() {
    }
}
