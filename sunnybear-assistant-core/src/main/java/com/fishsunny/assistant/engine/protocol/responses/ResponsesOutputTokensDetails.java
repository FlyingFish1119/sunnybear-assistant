package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

/** Responses API 输出侧明细：思考 / 推理 token 数。 */
@Data
@Accessors(chain = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesOutputTokensDetails {

    private Integer reasoning_tokens;

    public ResponsesOutputTokensDetails() {
    }
}
