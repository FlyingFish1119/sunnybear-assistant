package com.fishsunny.assistant.engine.protocol.standard.chat.option;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 00:54
 */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors
@JsonIgnoreProperties
public class StandardAIThinking {

    private String type;

    public StandardAIThinking() {
    }

    public StandardAIThinking(String type) {
        this.type = type;
    }
}
