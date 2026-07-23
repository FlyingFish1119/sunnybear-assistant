package com.fishsunny.assistant.engine.protocol.standard.chat.tools.register;

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
