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
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

@Getter
@Setter
@Accessors(chain = true)
public class ChatProvider {

    public static final ChatProvider DEFAULT = new ChatProvider();

    private Function<SystemProviderContext, String> systemProvider;
    private Function<ToolProviderContext, List<StandardToolRegister>> toolProvider;
    private Function<Map<String, Object>, Map<String, Object>> contextProvider;
    private Function<List<ChatMessage>, List<ChatMessage>> sessionMessageProvider;
    private Supplier<Settings> settingsSupplier;
    private Supplier<Boolean> enableSlashCommand;
    private Supplier<Boolean> enableSwitchPro;

    public ChatProvider() {
    }

    @Accessors(chain = true)
    public record SystemProviderContext(ChatSession chatSession, List<ChatMessage> originMessages) { }

    @Accessors(chain = true)
    public record ToolProviderContext(ChatSession chatSession, List<StandardToolRegister> toolRegisters) { }

    @Accessors
    public record Settings(AISettings chat, AISettings chatPro, AssistantSettings assistant) { }
}
