package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicToolResultContent extends AnthropicContentBlock {

    private final String type = "tool_result";

    private String tool_use_id;

    private String content;

    public AnthropicToolResultContent() {
    }

    public AnthropicToolResultContent(String toolUseId, String content) {
        this.tool_use_id = toolUseId;
        this.content = content;
    }
}
