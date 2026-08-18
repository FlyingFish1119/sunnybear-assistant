package com.fishsunny.assistant.engine.protocol.standard.chat.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.StandardContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.text.StandardTextContent;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardUserMessage extends StandardMessage {

    private final String role = "user";

    private List<StandardContent> content = new ArrayList<>();

    public StandardUserMessage() {
    }

    public StandardUserMessage(String text) {
        this.content = List.of(new StandardTextContent(text));
    }
}
