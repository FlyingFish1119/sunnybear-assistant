package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/** Responses API 输入侧明细：命中缓存的输入 token 数。 */
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesInputTokensDetails {

    private Integer cached_tokens;

    public ResponsesInputTokensDetails() {
    }
}
