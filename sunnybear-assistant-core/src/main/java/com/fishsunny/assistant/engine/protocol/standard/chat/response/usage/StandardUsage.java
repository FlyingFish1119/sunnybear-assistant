package com.fishsunny.assistant.engine.protocol.standard.chat.response.usage;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardUsage {

    private Integer prompt_tokens;

    private Integer completion_tokens;

    private Integer total_tokens;

    private Integer cached_tokens;

    private StandardPromptTokensDetails prompt_tokens_details;

    private StandardCompletionTokensDetails completion_tokens_details;

    public StandardUsage() {
    }
}
