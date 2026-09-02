package com.fishsunny.assistant.websocket.processor.slash.instance;

/*
 * @Usage 预览当前处理后的系统提示词（变量替换、知识库/记忆注入、自定义 provider 均已生效）
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/24
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandComponent;
import com.fishsunny.assistant.websocket.processor.slash.framework.SlashCommandHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Slf4j
@SlashCommandComponent("/preview")
public class PreviewSlashCommandHandler extends SlashCommandHandler {

    private final ChatMessageService chatMessageService;

    private final AssistantSettings assistantSettings;

    private final ObjectMapper objectMapper;

    public PreviewSlashCommandHandler(ChatMessageService chatMessageService,
                                      AssistantSettings assistantSettings,
                                      ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.assistantSettings = assistantSettings;
        this.objectMapper = objectMapper;
    }

    @Override
    protected List<String> resolveArgs(String originArgs) {
        // /preview 无参数，命令后附带的内容一律忽略
        return Collections.emptyList();
    }

    @Override
    protected void handle(List<String> args) throws Exception {
        // 从组装完成的 messages 中提取首条 system 消息，即最终发送给模型的系统提示词
        String systemPrompt = null;
        if (!CollectionUtils.isEmpty(messages)) {
            systemPrompt = messages.stream()
                    .filter(m -> ChatMessage.ROLE_SYSTEM.equals(m.getRole()))
                    .findFirst()
                    .map(ChatMessage::resolveText)
                    .orElse(null);
        }

        if (!StringUtils.hasText(systemPrompt)) {
            handleMessage("**未找到系统提示词**：当前上下文中没有可预览的 system 消息。");
            return;
        }

        String content = "## 🧩 当前生效的系统提示词\n\n````markdown\n" + systemPrompt.strip() + "\n````";
        handleMessage(content);
    }

    private void handleMessage(String content) {
        ChatMessage msg = new ChatMessage()
                .assistant(content, "", List.of())
                .makeInsertable(chatSession.getId(), ChatMessage.getParentId(messages), assistantSettings.getAssistantName());
        super.insertMessage(msg, chatMessageService);
        super.sendMessage(msg, objectMapper);
        super.resultMessage.add(msg);
    }
}
