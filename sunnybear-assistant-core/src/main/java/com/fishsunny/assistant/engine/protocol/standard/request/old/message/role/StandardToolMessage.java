package com.fishsunny.assistant.engine.protocol.standard.request.old.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardToolMessage extends StandardMessage {

    private final String role = "tool";

    private String tool_call_id;

    private String content;

    public StandardToolMessage() {
    }

    public StandardToolMessage(String tool_call_id, String content) {
        this.tool_call_id = tool_call_id;
        this.content = content;
    }
}
