package com.fishsunny.assistant.engine.protocol.text;

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.standard.response.usage.StandardUsage;
import com.fishsunny.assistant.engine.protocol.text.response.TextChoice;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TextAIResponse implements AIResponse {

    private String id;

    private String object;

    private Long created;

    private String model;

    private TextChoice[] choices = new TextChoice[0];

    public TextAIResponse setChoices(TextChoice[] choices) {
        this.choices = choices == null ? new TextChoice[0] : choices;
        return this;
    }

    private StandardUsage usage;

    private String system_fingerprint;

    public TextAIResponse() {
    }
}
