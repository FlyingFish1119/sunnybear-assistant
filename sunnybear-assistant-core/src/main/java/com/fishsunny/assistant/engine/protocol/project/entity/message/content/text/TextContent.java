package com.fishsunny.assistant.engine.protocol.project.entity.message.content.text;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 20:28
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class TextContent implements MessageContent {

    private final String type = "text";

    private String content;

    public TextContent() {
    }

    public TextContent(String content) {
        this.content = content;
    }
}
