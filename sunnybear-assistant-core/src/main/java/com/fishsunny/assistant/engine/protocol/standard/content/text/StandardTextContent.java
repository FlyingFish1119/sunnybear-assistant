package com.fishsunny.assistant.engine.protocol.standard.content.text;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.content.StandardContent;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardTextContent implements StandardContent {

    private final String type = "text";

    private String text;

    public StandardTextContent(String text) {
        this.text = text;
    }

    public StandardTextContent() {
    }
}
