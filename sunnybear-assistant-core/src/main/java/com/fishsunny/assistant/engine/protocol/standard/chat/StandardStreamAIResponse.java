package com.fishsunny.assistant.engine.protocol.standard.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
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

    private String system_fingerprint;

    public StandardStreamAIResponse() {
    }
}
