package com.fishsunny.assistant.engine.protocol.standard.request.old.message.role;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.content.StandardContent;
import com.fishsunny.assistant.engine.protocol.standard.content.text.StandardTextContent;
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

    public StandardUserMessage setContent(List<StandardContent> content) {
        this.content = content == null ? new ArrayList<>() : content;
        return this;
    }

    public StandardUserMessage() {
    }

    public StandardUserMessage(String text) {
        this.content = List.of(new StandardTextContent(text));
    }
}
