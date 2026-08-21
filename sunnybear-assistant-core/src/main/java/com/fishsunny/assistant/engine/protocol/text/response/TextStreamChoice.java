package com.fishsunny.assistant.engine.protocol.text.response;

import com.fishsunny.assistant.engine.protocol.standard.chat.response.usage.StandardUsage;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TextStreamChoice {

    private Integer index;

    private TextMessage delta;

    private StandardUsage usage;

    private String logprobs;

    private String finish_reason;

    public TextStreamChoice() {
    }
}
