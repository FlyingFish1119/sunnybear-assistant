package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.anthropic.message.AnthropicMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.tools.AnthropicToolRegister;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicAIRequest implements AIRequest {

    private String model;

    private List<AnthropicMessage> messages = new ArrayList<>();

    /**
     * System prompt — Anthropic uses a top-level "system" field instead of
     * a message with role="system". Can be a single string or a list of text blocks.
     */
    private Object system;

    private Boolean stream = false;

    private Integer max_tokens;

    private Double temperature;

    private Double top_p;

    private Integer top_k;

    private List<String> stop_sequences;

    private List<AnthropicToolRegister> tools = new ArrayList<>();

    private AnthropicThinking thinking;

    public AnthropicAIRequest() {
    }
}
