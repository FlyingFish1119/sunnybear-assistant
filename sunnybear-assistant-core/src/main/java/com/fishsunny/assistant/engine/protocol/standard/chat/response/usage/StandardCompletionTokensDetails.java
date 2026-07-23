package com.fishsunny.assistant.engine.protocol.standard.chat.response.usage;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardCompletionTokensDetails {

    private Integer reasoning_tokens;

    public StandardCompletionTokensDetails() {
    }
}
