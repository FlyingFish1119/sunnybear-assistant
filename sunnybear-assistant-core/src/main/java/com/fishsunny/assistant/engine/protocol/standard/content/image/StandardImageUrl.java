package com.fishsunny.assistant.engine.protocol.standard.content.image;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardImageUrl {

    private String url;
}
