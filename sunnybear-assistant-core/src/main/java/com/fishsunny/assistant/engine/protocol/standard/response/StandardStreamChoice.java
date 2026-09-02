package com.fishsunny.assistant.engine.protocol.standard.response;

import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.response.usage.StandardUsage;
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
