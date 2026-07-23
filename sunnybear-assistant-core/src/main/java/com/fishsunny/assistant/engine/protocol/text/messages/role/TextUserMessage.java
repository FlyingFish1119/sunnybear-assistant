package com.fishsunny.assistant.engine.protocol.text.messages.role;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 10:13
 */


import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextUserMessage implements TextMessage {

    private final String role = "user";

    private String content;

    public TextUserMessage() {
    }

    public TextUserMessage(String content) {
        this.content = content;
    }
}
