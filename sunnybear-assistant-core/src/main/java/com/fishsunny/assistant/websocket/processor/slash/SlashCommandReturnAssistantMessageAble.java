package com.fishsunny.assistant.websocket.processor.slash;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/20 17:06
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;

import java.util.List;

public interface SlashCommandReturnAssistantMessageAble {

    List<ChatMessage> getAssistantMessages();
}
