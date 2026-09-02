package com.fishsunny.assistant.engine.protocol.standard.response;

import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
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
