package com.fishsunny.assistant.engine.protocol.standard.request.old.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

@EqualsAndHashCode(callSuper = true)
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
