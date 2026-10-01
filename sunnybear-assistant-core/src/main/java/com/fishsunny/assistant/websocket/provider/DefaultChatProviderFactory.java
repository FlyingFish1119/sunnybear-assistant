package com.fishsunny.assistant.websocket.provider;

/*
 * @Usage 默认 ChatProvider 工厂 —— 集中承载原先散落在 ChatProcessor 里的默认行为。
 *        各入口（普通对话 / 角色 / 世界）以 newProvider() 为基底派生并覆盖单项，
 *        调用方不再需要写 if (provider.getX() != null) 的分支。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ChatPromptService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class DefaultChatProviderFactory {

    private final ChatPromptService chatPromptService;
    private final AISettings chatAiSettings;
    private final AISettings chatProAiSettings;
    private final AssistantSettings assistantSettings;

    public DefaultChatProviderFactory(ChatPromptService chatPromptService,
                                      @Qualifier(AISettings.CHAT) AISettings chatAiSettings,
                                      @Qualifier(AISettings.CHAT_PRO) AISettings chatProAiSettings,
                                      AssistantSettings assistantSettings) {
        this.chatPromptService = chatPromptService;
        this.chatAiSettings = chatAiSettings;
        this.chatProAiSettings = chatProAiSettings;
        this.assistantSettings = assistantSettings;
    }

    /** 用调用方给出的设置覆盖默认：字段为 null 的部分回落全局配置（保留“未配置则继承全局”语义） */
    public ChatProvider.Settings withDefaults(ChatProvider.Settings override) {
        if (override == null) {
            return defaultSettings();
        }
        return new ChatProvider.Settings(
                override.chat() != null ? override.chat() : chatAiSettings,
                override.chatPro() != null ? override.chatPro() : chatProAiSettings,
                override.assistant() != null ? override.assistant() : assistantSettings);
    }

    /** 新建一个带全部默认实现的 ChatProvider；调用方可继续 setXxx 覆盖单项 */
    public ChatProvider newProvider() {
        return new ChatProvider()
                .setSystemProvider(this::defaultSystemProviderContext)
                .setSettingsSupplier(this::defaultSettings)
                .setAfterComplete(this::defaultAfterComplete);
    }

    /** 全局默认设置（chat / chatPro / assistant 三者均取自系统配置） */
    private ChatProvider.Settings defaultSettings() {
        return new ChatProvider.Settings(chatAiSettings, chatProAiSettings, assistantSettings);
    }

    private String defaultSystemProviderContext(ChatProvider.SystemProviderContext ctx) {
        try {
            return chatPromptService.defaultSystemPrompt(
                    ctx, ctx.assistantSettings(), ctx.effectiveSettings().getModel(), ctx.session());
        } catch (Exception e) {
            throw new RuntimeException("构建默认系统提示词失败: " + e.getMessage(), e);
        }
    }

    private void defaultAfterComplete(ChatProvider.CompleteData data) {
    }
}
