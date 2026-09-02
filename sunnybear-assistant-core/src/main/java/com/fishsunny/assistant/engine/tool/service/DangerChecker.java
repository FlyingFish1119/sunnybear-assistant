package com.fishsunny.assistant.engine.tool.service;


import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.settings.AISettings;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * DangerChecker
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/2 10:58
 */
@Component
public class DangerChecker {

    private final ChatHttpHandler chatHttpHandler;
    private final AISettings aiSettings;

    public DangerChecker(ChatHttpHandler chatHttpHandler, @Qualifier(AISettings.CUB) AISettings aiSettings) {
        this.chatHttpHandler = chatHttpHandler;
        this.aiSettings = aiSettings;
    }

    public boolean checkDanger(String systemPrompt, String userPrompt) throws Exception {
        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(userPrompt)
                ));
        AtomicBoolean isDanger = new AtomicBoolean(false);
        AtomicReference<String> exceptionMessage = new AtomicReference<>("");

        // 创建数据
        ChatHttpHandler.TranslateData data = new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(), aiSettings.getAdapterName(), aiSettings.getStream(), request);
        // 创建处理器
        ChatHttpHandler.TranslateHandler handler = new ChatHttpHandler.TranslateHandler(null,
                ((result, lastRes) -> {
                    String answer = result.content() != null ? result.content().trim().toLowerCase() : "";
                    if ("true".equals(answer)) {
                        isDanger.set(true);
                    } else if ("false".equals(answer)) {
                        isDanger.set(false);
                    } else {
                        exceptionMessage.set("危险解析器输出了无法识别的格式[" + result.content() + "]，工具停止执行。");
                    }
                })
        );

        chatHttpHandler.translate(data, handler);
        if (StringUtils.hasText(exceptionMessage.get())) {
            throw new ToolExecutor.ToolExecuteException(exceptionMessage.get());
        }
        return isDanger.get();
    }
}
