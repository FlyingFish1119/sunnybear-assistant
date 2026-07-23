package com.fishsunny.assistant.engine.protocol.text.messages.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextSystemMessage implements TextMessage {

    private final String role = "system";

    private String content;

    public TextSystemMessage() {
    }

    public TextSystemMessage(String content) {
        this.content = content;
    }
}
