package com.fishsunny.assistant.engine.protocol.standard.chat.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.request.StandardToolRequest;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardAssistantMessage extends StandardMessage {

    private final String role = "assistant";

    private String content;

    private String reasoning_content;

    private List<StandardToolRequest> tool_calls = new ArrayList<>();

    public StandardAssistantMessage setTool_calls(List<StandardToolRequest> tool_calls) {
        this.tool_calls = tool_calls == null ? new ArrayList<>() : tool_calls;
        return this;
    }

    public StandardAssistantMessage() {
    }

    public StandardAssistantMessage(String content, String reasoning_content) {
        this.content = content;
        this.reasoning_content = reasoning_content;
    }
}
