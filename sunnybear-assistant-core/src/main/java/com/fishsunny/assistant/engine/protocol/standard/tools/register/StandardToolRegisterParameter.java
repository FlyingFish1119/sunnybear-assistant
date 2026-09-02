package com.fishsunny.assistant.engine.protocol.standard.tools.register;

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
@Accessors(chain = true)
public class StandardToolRegisterParameter {

    private final String type = "object";

    private Map<String, StandardToolRegisterProperty> properties = new HashMap<>();

    private List<String> required = new ArrayList<>();

    public StandardToolRegisterParameter() {
    }
}
