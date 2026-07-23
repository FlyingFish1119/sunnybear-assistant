package com.fishsunny.assistant.engine.protocol.project;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 07:21
 */

import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.settings.ChatSettings;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ChatRequest implements AIRequest {

    private List<ChatMessage> messages = new ArrayList<>();

    private ChatSettings settings;

    private List<StandardToolRegister> tools = new ArrayList<>();

    public ChatRequest() {
    }

    public ChatRequest loadSettings(AISettings settings) {
        this.settings = new ChatSettings(settings);
        return this;
    }
}
