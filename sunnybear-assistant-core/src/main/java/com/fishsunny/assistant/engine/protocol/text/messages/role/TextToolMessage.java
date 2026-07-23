package com.fishsunny.assistant.engine.protocol.text.messages.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 10:14
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextToolMessage implements TextMessage {

    private final String role = "tool";

    private String tool_call_id;

    private String content;

    public TextToolMessage() {
    }

    public TextToolMessage(String tool_call_id, String content) {
        this.tool_call_id = tool_call_id;
        this.content = content;
    }
}
