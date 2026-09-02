package com.fishsunny.assistant.engine.protocol.text.messages.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 10:15
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.tools.request.StandardToolRequest;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextAssistantMessage implements TextMessage {

    private final String role = "assistant";

    private String content;

    private String reasoning_content;

    private List<StandardToolRequest> tool_calls = new ArrayList<>();

    public TextAssistantMessage setTool_calls(List<StandardToolRequest> tool_calls) {
        this.tool_calls = tool_calls;
        return this;
    }

    public TextAssistantMessage() {
    }

    public TextAssistantMessage(String content, String reasoning_content) {
        this.content = content;
        this.reasoning_content = reasoning_content;
    }
}
