package com.fishsunny.assistant.engine.protocol.standard.chat;

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.standard.chat.response.StandardStreamChoice;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardStreamAIResponse implements AIResponse {

    private String id;

    private String object;

    private Long created;

    private String model;

    private StandardStreamChoice[] choices = new StandardStreamChoice[0];

    public StandardStreamAIResponse setChoices(StandardStreamChoice[] choices) {
        this.choices = choices == null ? new StandardStreamChoice[0] : choices;
        return this;
    }

    private String system_fingerprint;

    public StandardStreamAIResponse() {
    }
}
