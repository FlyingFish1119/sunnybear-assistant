package com.fishsunny.assistant.engine.protocol.text;

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.text.response.TextStreamChoice;
import lombok.Data;

@Data
public class TextStreamAIResponse implements AIResponse {

    private String id;

    private String object;

    private Long created;

    private String model;

    private TextStreamChoice[] choices = new TextStreamChoice[0];

    private String system_fingerprint;

    public TextStreamAIResponse() {
    }
}
