package com.fishsunny.assistant.engine.protocol.anthropic.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicToolRegister {

    private String name;

    private String description;

    private Map<String, Object> input_schema;

    public AnthropicToolRegister() {
    }
}
