package com.fishsunny.assistant.engine.protocol.text.response;

import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TextChoice {

    private Integer index;

    private TextMessage message;

    private String logprobs;

    private String finish_reason;

    public TextChoice() {
    }
}
