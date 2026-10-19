package com.fishsunny.assistant.plug.qq.component;

/*
 * @Usage QQ 对话 WebSocket 处理器 —— 在全局主提示词之后追加 QQ 环境说明，让 AI 明确当前在 QQ 里说话
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/19 14:21
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import com.fishsunny.assistant.websocket.provider.ChatProvider;
import com.fishsunny.assistant.websocket.provider.DefaultChatProviderFactory;
import com.fishsunny.assistant.websocket.request.ChatMessageRequestHandlerFactory;
import com.fishsunny.assistant.websocket.ws.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.function.Function;

/**
 * QQ 渠道的对话处理器。
 *
 * <p>与网页端共用同一条对话管道，唯一差异在系统提示词：以默认 provider（全局主提示词 + 知识库 + 记忆）为基底，
 * 在其后追加 {@code plug.qq.prompt} 配置的 QQ 环境说明，用于约束纯文本输出、篇幅与「过程不外显」。
 * 工具表与设置项均不改动，QQ 与网页端的能力保持一致。
 */
@Slf4j
@Component("qqChatWebSocketHandler")
public class QQChatWebSocketHandler extends ChatWebSocketHandler {

    private final String qqPrompt;

    public QQChatWebSocketHandler(ServiceProcessor serviceProcessor,
                                  TempChatProcessor tempChatProcessor,
                                  ChatProcessor chatProcessor,
                                  DefaultChatProviderFactory defaultChatProviderFactory,
                                  TaskExecutor chatAsyncExecutor,
                                  ObjectMapper objectMapper,
                                  SessionMessageBus sessionMessageBus,
                                  ChatMessageRequestHandlerFactory chatMessageRequestHandlerFactory,
                                  @Value("${plug.qq.prompt:}") String qqPrompt) {
        super(serviceProcessor, tempChatProcessor, chatProcessor, defaultChatProviderFactory, chatAsyncExecutor, objectMapper, sessionMessageBus, chatMessageRequestHandlerFactory);
        this.qqPrompt = qqPrompt;
    }

    /**
     * 会话感知版 ChatProvider：在默认系统提示词之后追加 QQ 环境说明。
     * 未配置 {@code plug.qq.prompt} 时行为与网页端完全一致，不会注入空段落。
     */
    @Override
    public ChatProvider chatToAiProvider(ChatSession chatSession) {
        ChatProvider provider = defaultChatProviderFactory.newProvider();
        if (!StringUtils.hasText(qqPrompt)) {
            log.warn("未配置 plug.qq.prompt，QQ 对话将沿用网页端系统提示词");
            return provider;
        }

        // 以工厂产出的默认提示词构造器为基底做函数级包装，保留知识库、记忆注入与变量替换的全部行为
        Function<ChatProvider.SystemProviderContext, String> defaultSystemProvider = provider.getSystemProvider();
        return provider.setSystemProvider(context -> defaultSystemProvider.apply(context) + "\n\n" + qqPrompt);
    }
}
