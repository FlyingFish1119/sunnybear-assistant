package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 06:56
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.cancel.ChatCancelContext;
import com.fishsunny.assistant.utils.ContextCompressor;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatUsage;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tts.TTSSettings;
import com.fishsunny.assistant.engine.tool.service.ToolVisibilityPolicy;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.SessionFileManager;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandExecutor;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;

/**
 * 本类主要用于处理核心对话逻辑，包括消息的传输与落盘
 */
@Component
@Slf4j
public class ChatProcessor {

    private final AssistantSettings assistantSettings;
    private final AISettings aiSettings;
    private final AISettings chatProAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final ChatPromptService chatPromptService;
    private final SlashCommandExecutor slashCommandExecutor;
    private final SessionFileManager sessionFileManager;
    private final ToolVisibilityPolicy toolVisibilityPolicy;
    private final ContextCompressor contextCompressor;
    private final StandardChatTranslateFactory standardChatTranslateFactory;

    public ChatProcessor(AssistantSettings assistantSettings,
                            ToolExecutor toolExecutor,
                            ChatPromptService chatPromptService,
                            @Qualifier(AISettings.CHAT) AISettings aiSettings,
                            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
                            SlashCommandExecutor slashCommandExecutor,
                            ChatHttpHandler chatHttpHandler,
                            SessionFileManager sessionFileManager,
                            ToolVisibilityPolicy toolVisibilityPolicy,
                            ContextCompressor contextCompressor,
                            StandardChatTranslateFactory standardChatTranslateFactory
                         ) {
        this.assistantSettings = assistantSettings;
        this.toolExecutor = toolExecutor;
        this.chatPromptService = chatPromptService;
        this.aiSettings = aiSettings;
        this.chatProAISettings = chatProAISettings;
        this.slashCommandExecutor = slashCommandExecutor;
        this.chatHttpHandler = chatHttpHandler;
        this.sessionFileManager = sessionFileManager;
        this.toolVisibilityPolicy = toolVisibilityPolicy;
        this.contextCompressor = contextCompressor;
        this.standardChatTranslateFactory = standardChatTranslateFactory;

    }
    /**
     * 核心对话处理逻辑
     *
     * @param enableTts 本消息请求 AI 回复朗读：逐句音频随文本推 WS 帧（###TTS_AUDIO###），
     *                  整轮完整音频随 assistant 消息 extension 落库（需 engine.tts.enable）
     */
    public List<ChatMessage> chatToAi(List<ChatMessage> originMessages,
                                      ChatSession chatSession,
                                      WebSocketSession session,
                                      ChatProvider chatProvider,
                                      boolean enableTts
                                      ) throws Exception {
        if (chatProvider == null) {
            throw new UserException("无效的 ChatProvider");
        }
        // 包装会话，用于消息的广播

        AISettings activeAISettings = null;
        AISettings activeChatProAISettings = null;
        AssistantSettings activeAssistantSettings = null;
        if (chatProvider.getSettingsSupplier() != null && chatProvider.getSettingsSupplier().get() != null) {
            ChatProvider.Settings settings = chatProvider.getSettingsSupplier().get();
            activeAISettings = settings.chat();
            activeChatProAISettings = settings.chatPro();
            activeAssistantSettings = settings.assistant();
        }
        if (activeAISettings == null) {
            activeAISettings = this.aiSettings;
        }
        if (activeChatProAISettings == null) {
            activeChatProAISettings = this.chatProAISettings;
        }
        if (activeAssistantSettings == null) {
            activeAssistantSettings = this.assistantSettings;
        }

        // 根据 session 的 enable_pro 标记选择使用 chat 还是 chat_pro 模型
        AISettings effectiveAISettings = Boolean.TRUE.equals(chatSession.getEnablePro()) ? activeChatProAISettings : activeAISettings;

        // 获取系统提示
        ChatProvider.SystemProviderContext context = new ChatProvider.SystemProviderContext(chatSession, originMessages);
        String systemPrompt;
        if (chatProvider.getSystemProvider() != null) {
            systemPrompt = chatProvider.getSystemProvider().apply(context);
        } else {
            systemPrompt = chatPromptService.defaultSystemPrompt(context, activeAssistantSettings, effectiveAISettings.getModel(), session);
        }

        List<ChatMessage> messages = new ArrayList<>();
        if (chatProvider.getSessionMessageProvider() != null) {
            originMessages = chatProvider.getSessionMessageProvider().apply(originMessages);
        }
        messages.add(new ChatMessage().system(systemPrompt));
        messages.addAll(originMessages);

        ChatRequest request = new ChatRequest()
                .setMessages(messages)
                .loadSettings(effectiveAISettings);

        ChatMessage userMessage = ObjectUtils.getLast(request.getMessages());
        if (userMessage == null) {
            throw new UserException("用户消息为空");
        }
        if (!ChatMessage.ROLE_USER.equals(userMessage.getRole()) && !ChatMessage.ROLE_TOOL.equals(userMessage.getRole())) {
            throw new UserException("用户消息角色无效: " + userMessage.getRole());
        }

        // 注入工具：按 kit 排除用户关掉的工具集与声明不开放的工具集，再按名字排除子 Agent 本体
        List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegisterExcluding(
                toolExecutor, toolVisibilityPolicy.excludedKits(), toolVisibilityPolicy.excludedHandlers());
        if (chatProvider.getToolProvider() != null) {
            ChatProvider.ToolProviderContext toolCtx = new ChatProvider.ToolProviderContext(chatSession, toolRegisters);
            toolRegisters = chatProvider.getToolProvider().apply(toolCtx);
        }
        request.setTools(toolRegisters);

        SlashCommandHandler.SlashCommandContext slashCommandContext = new SlashCommandHandler.SlashCommandContext(
                userMessage.resolveText(), session, chatSession, messages, new ArrayList<>()
        );
        boolean enableSlashCommand = true;
        if (chatProvider.getEnableSlashCommand() != null) {
            enableSlashCommand = Boolean.TRUE.equals(chatProvider.getEnableSlashCommand().get());
        }
        if (enableSlashCommand && slashCommandExecutor.runSlashFactory(slashCommandContext)) {
            return slashCommandContext.resultMessage();
        }

        List<ChatMessage> collector = new ArrayList<>();

        StandardChatTranslateFactory.ReActDependence reActDependence = new StandardChatTranslateFactory.ReActDependence(activeAssistantSettings, session);
        StandardChatTranslateFactory.ReActOption reActOption = new StandardChatTranslateFactory.ReActOption(chatProvider);
        StandardChatTranslateFactory.ReActData reActData = new StandardChatTranslateFactory.ReActData(collector, chatSession, request);

        reactLoop(effectiveAISettings, enableTts, reActDependence, reActOption, reActData);

        return collector;
    }

    /**
     * 工具调用循环
     */
    private void reactLoop(
            AISettings effectiveAISettings,
            boolean enableTts,
            StandardChatTranslateFactory.ReActDependence reActDependence,
            StandardChatTranslateFactory.ReActOption reActOption,
            StandardChatTranslateFactory.ReActData reActData
    ) throws Exception {
        ChatRequest request = reActData.request();
        ChatSession chatSession = reActData.chatSession();
        WebSocketSession session = reActDependence.session();

        // 定义响应处理
        ChatHttpHandler.InTranslateCallback translate = standardChatTranslateFactory.createInTranslateCallback(reActDependence, reActData);
        // 定义回调
        ChatHttpHandler.CompleteCallback complete = standardChatTranslateFactory.createOnCompleteCallback(reActDependence, reActOption, reActData);

        while (!ChatCancelContext.isCancelled()) {

            // 上下文达到用户配置上限时压缩历史：总结旧对话、清库并重建 root 用户消息，然后继续本轮
            contextCompressor.maybeCompress(request, chatSession, session);

            // 把会话文件引用展开为 data URI（图片/音视频）或文本（可读文件），再发给模型
            request.setMessages(sessionFileManager.loadSessionFile(request.getMessages()));

            ChatHttpHandler.TranslateData data = new ChatHttpHandler.TranslateData(
                    chatSession.getId(), effectiveAISettings.getAdapterName(), request);

            ChatHttpHandler.TranslateOption option = new ChatHttpHandler.TranslateOption()
                    .setStream(request.getSettings().getStream())
                    .setEnableTTS(enableTts);
            ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler(translate, complete);
            if (!chatHttpHandler.translate(data, translateHandler, option)) {
                break;
            }
        }
    }
}
