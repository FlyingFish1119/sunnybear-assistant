package com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import com.fishsunny.assistant.engine.protocol.standard.content.StandardContent;
import com.fishsunny.assistant.engine.protocol.standard.content.text.StandardTextContent;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultimodalUserMessage extends MultimodalMessage {

    private final String role = "user";

    private List<StandardContent> content = new ArrayList<>();

    public MultimodalUserMessage setContent(List<StandardContent> content) {
        this.content = content == null ? new ArrayList<>() : content;
        return this;
    }

    public MultimodalUserMessage() {
    }

    public MultimodalUserMessage(String text) {
        this.content = List.of(new StandardTextContent(text));
    }
}
