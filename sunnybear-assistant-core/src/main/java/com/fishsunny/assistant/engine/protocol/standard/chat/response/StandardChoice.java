package com.fishsunny.assistant.engine.protocol.standard.chat.response;

import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardChoice {

    private Integer index;

    private StandardMessage message;

    private String logprobs;

    private String finish_reason;

    public StandardChoice() {
    }
}
