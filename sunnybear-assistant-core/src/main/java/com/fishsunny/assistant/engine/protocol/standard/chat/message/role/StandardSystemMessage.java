package com.fishsunny.assistant.engine.protocol.standard.chat.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardSystemMessage extends StandardMessage {

    private final String role = "system";

    private String content;

    public StandardSystemMessage() {
    }

    public StandardSystemMessage(String content) {
        this.content = content;
    }
}
