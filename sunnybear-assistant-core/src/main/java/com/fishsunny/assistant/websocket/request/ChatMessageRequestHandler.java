package com.fishsunny.assistant.websocket.request;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:29
 */

import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.websocket.ws.SynchronizedWebSocketSession;

public interface ChatMessageRequestHandler {


    ChatSessionModeParseResult handle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception;

    String getMode();

}
