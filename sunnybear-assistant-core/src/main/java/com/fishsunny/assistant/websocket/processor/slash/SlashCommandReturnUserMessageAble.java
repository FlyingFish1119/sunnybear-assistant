package com.fishsunny.assistant.websocket.processor.slash;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/20 17:06
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import org.springframework.web.socket.WebSocketSession;

public interface SlashCommandReturnUserMessageAble {

    ChatMessage getUserMessage();
}
