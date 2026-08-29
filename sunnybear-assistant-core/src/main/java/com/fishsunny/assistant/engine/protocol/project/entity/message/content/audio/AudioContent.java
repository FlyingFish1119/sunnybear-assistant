package com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 21:13
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.constants.ContentTypeVariable;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class AudioContent extends MessageContent {

    private final String type = ContentTypeVariable.AUDIO;

    private String url;

    public AudioContent() {
    }

    public AudioContent(String url) {
        this.url = url;
    }
}
