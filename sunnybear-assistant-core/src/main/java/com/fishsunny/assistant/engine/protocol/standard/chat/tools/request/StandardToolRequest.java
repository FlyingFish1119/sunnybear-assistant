package com.fishsunny.assistant.engine.protocol.standard.chat.tools.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardToolRequest {

    private Integer index;

    private String id;

    private final String type = "function";

    private StandardToolRequestFunction function;

    public StandardToolRequest() {
    }
}
