package com.fishsunny.assistant.engine.protocol.standard.tools.register;

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardToolRegisterProperty {

    private String type;

    private String description;

    public StandardToolRegisterProperty() {
    }
}
