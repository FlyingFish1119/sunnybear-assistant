package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicImageSource {

    private final String type = "base64";

    private String media_type;

    private String data;

    public AnthropicImageSource() {
    }

    public AnthropicImageSource(String mediaType, String data) {
        this.media_type = mediaType;
        this.data = data;
    }
}
