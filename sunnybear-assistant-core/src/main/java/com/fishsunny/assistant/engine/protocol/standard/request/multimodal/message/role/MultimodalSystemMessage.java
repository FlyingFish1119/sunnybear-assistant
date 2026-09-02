package com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultimodalSystemMessage extends MultimodalMessage {

    private final String role = "system";

    private String content;

    public MultimodalSystemMessage() {
    }

    public MultimodalSystemMessage(String content) {
        this.content = content;
    }
}
