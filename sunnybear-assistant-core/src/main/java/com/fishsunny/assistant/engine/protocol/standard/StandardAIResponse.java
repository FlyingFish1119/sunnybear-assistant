package com.fishsunny.assistant.engine.protocol.standard;

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import com.fishsunny.assistant.engine.protocol.UsageSource;
import com.fishsunny.assistant.engine.protocol.standard.response.StandardChoice;
import com.fishsunny.assistant.engine.protocol.standard.response.usage.StandardUsage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardAIResponse implements AIResponse, UsageSource {

    private String id;

    private String object;

    private Long created;

    private String model;

    private StandardChoice[] choices = new StandardChoice[0];

    public StandardAIResponse setChoices(StandardChoice[] choices) {
        this.choices = choices == null ? new StandardChoice[0] : choices;
        return this;
    }

    private StandardUsage usage;

    private String system_fingerprint;

    public StandardAIResponse() {
    }

    @Override
    public TokenUsage toTokenUsage() {
        return StandardUsage.toTokenUsage(usage);
    }
}
