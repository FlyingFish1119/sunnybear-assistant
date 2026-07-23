package com.fishsunny.assistant.engine.protocol.standard.chat.tools.request;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardToolRequestFunction {

    private String name;

    private String arguments;

    public StandardToolRequestFunction() {
    }
}
