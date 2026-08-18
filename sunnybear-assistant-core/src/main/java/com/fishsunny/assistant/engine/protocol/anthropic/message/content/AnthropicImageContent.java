package com.fishsunny.assistant.engine.protocol.anthropic.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicImageContent extends AnthropicContentBlock {

    private final String type = "image";

    private AnthropicImageSource source;

    public AnthropicImageContent() {
    }

    public AnthropicImageContent(AnthropicImageSource source) {
        this.source = source;
    }
}
