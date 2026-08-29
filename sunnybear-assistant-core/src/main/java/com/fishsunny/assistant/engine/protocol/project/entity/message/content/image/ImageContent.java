package com.fishsunny.assistant.engine.protocol.project.entity.message.content.image;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 20:28
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.constants.ContentTypeVariable;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class ImageContent extends MessageContent {

    private final String type = ContentTypeVariable.IMAGE;

    private String url;

    public ImageContent() {
    }

    public ImageContent(String url) {
        this.url = url;
    }
}
