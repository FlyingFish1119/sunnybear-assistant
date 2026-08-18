package com.fishsunny.assistant.engine.protocol.project.entity.message.content.video;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 21:13
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.variable.ContentTypeVariable;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class VideoContent extends MessageContent {

    private final String type = ContentTypeVariable.VIDEO;

    private String url;

    public VideoContent() {
    }

    public VideoContent(String url) {
        this.url = url;
    }
}
