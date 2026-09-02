package com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultimodalAssistantMessage extends MultimodalMessage {

    private final String role = "assistant";

    private String content;

    private String reasoning_content;

    private List<StandardToolRequest> tool_calls = new ArrayList<>();

    public MultimodalAssistantMessage setTool_calls(List<StandardToolRequest> tool_calls) {
        this.tool_calls = tool_calls == null ? new ArrayList<>() : tool_calls;
        return this;
    }

    public MultimodalAssistantMessage() {
    }

    public MultimodalAssistantMessage(String content, String reasoning_content) {
        this.content = content;
        this.reasoning_content = reasoning_content;
    }
}
