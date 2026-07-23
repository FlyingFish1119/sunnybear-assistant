package com.fishsunny.assistant.engine.protocol.standard.chat.response;

import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.response.usage.StandardUsage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardStreamChoice {

    private Integer index;

    private StandardMessage delta;

    private StandardUsage usage;

    private String logprobs;

    private String finish_reason;

    public StandardStreamChoice() {
    }
}
