package com.fishsunny.assistant.engine.protocol.standard.response.usage;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardPromptTokensDetails {

    private Integer cached_tokens;

    public StandardPromptTokensDetails() {
    }
}
