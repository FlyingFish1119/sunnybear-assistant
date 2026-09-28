package com.fishsunny.assistant.websocket.processor.request;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:47
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.function.Supplier;

@Data
@Accessors(chain = true)
public class ChatMessageRequestProvider {

    public static final ChatMessageRequestProvider DEFAULT = new ChatMessageRequestProvider()
            .setEnableProModel(() -> true)
            .setSessionType(() -> "chat");

    private Supplier<Boolean> enableProModel;
    private Supplier<String> sessionType;
}
