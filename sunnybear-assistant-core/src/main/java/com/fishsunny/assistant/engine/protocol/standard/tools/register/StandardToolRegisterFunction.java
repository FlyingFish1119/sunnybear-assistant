package com.fishsunny.assistant.engine.protocol.standard.tools.register;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardToolRegisterFunction {

    private String name;

    private String description;

    private StandardToolRegisterParameter parameters;

    public StandardToolRegisterFunction() {
    }
}
