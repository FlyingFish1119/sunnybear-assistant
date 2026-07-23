package com.fishsunny.assistant.websocket;

/*
 * @Usage
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13 14:38
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Getter
@Setter
@Accessors(chain = true)
public class ChatProvider {

    private Function<SystemProviderContext, String> systemProvider;
    private Function<ToolProviderContext, List<StandardToolRegister>> toolProvider;
    private Function<Map<String, Object>, Map<String, Object>> contextProvider;

    public ChatProvider() {
    }

    @Accessors(chain = true)
        public record SystemProviderContext(ChatSession chatSession, List<ChatMessage> originMessages) {
    }

    @Accessors(chain = true)
        public record ToolProviderContext(ChatSession chatSession, List<StandardToolRegister> toolRegisters) {
    }
}
