package com.fishsunny.assistant.engine.protocol.standard.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.standard.chat.response.StandardChoice;
import com.fishsunny.assistant.engine.protocol.standard.chat.response.usage.StandardUsage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardAIResponse implements AIResponse {

    private String id;

    private String object;

    private Long created;

    private String model;

    private StandardChoice[] choices = new StandardChoice[0];

    private StandardUsage usage;

    private String system_fingerprint;

    public StandardAIResponse() {
    }
}
