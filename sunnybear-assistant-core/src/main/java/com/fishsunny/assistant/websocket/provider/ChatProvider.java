package com.fishsunny.assistant.websocket.provider;

/*
 * @Usage
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13 14:38
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

@Getter
@Setter
@Accessors(chain = true)
public class ChatProvider {

    private Function<SystemProviderContext, String> systemProvider;

    /** 默认：不改动调用方已构建的工具表 */
    private Function<ToolProviderContext, List<StandardToolRegister>> toolProvider = ToolProviderContext::toolRegisters;

    /** 默认：原样返回上下文 */
    private Function<Map<String, Object>, Map<String, Object>> contextProvider = Function.identity();

    /** 默认：不折叠会话历史 */
    private Function<List<ChatMessage>, List<ChatMessage>> sessionMessageProvider = Function.identity();

    /** 默认：不干预落库前的助手消息 */
    private Function<ChatMessage, ChatMessage> beforeSaveAssistantProvider = Function.identity();

    private Consumer<CompleteData> afterComplete = completeData -> {};

    private Supplier<Settings> settingsSupplier = () -> null;

    /** 默认：开启斜杠命令 */
    private Supplier<Boolean> enableSlashCommand = () -> true;

    public ChatProvider() {
    }

    /** 以当前 provider 为基底派生一个可修改副本：函数实现共享，字段级覆盖 */
    public ChatProvider derive() {
        return new ChatProvider()
                .setSystemProvider(systemProvider)
                .setToolProvider(toolProvider)
                .setContextProvider(contextProvider)
                .setSessionMessageProvider(sessionMessageProvider)
                .setBeforeSaveAssistantProvider(beforeSaveAssistantProvider)
                .setSettingsSupplier(settingsSupplier)
                .setEnableSlashCommand(enableSlashCommand);
    }

    public record SystemProviderContext(ChatSession chatSession, List<ChatMessage> originMessages,
                                        WebSocketSession session, AISettings effectiveSettings,
                                        AssistantSettings assistantSettings) { }

    public record ToolProviderContext(ChatSession chatSession, List<StandardToolRegister> toolRegisters) { }

    public record Settings(AISettings chat, AISettings chatPro, AssistantSettings assistant) { }

    public record CompleteData(ChatSession chatSession, List<ChatMessage> messages) { }
}
