package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage 默认系统提示词构造服务：变量替换 + 知识库/记忆注入
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/24
 */

import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.constants.PromptReplaceVariable;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.MemorySettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.websocket.ChatProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.List;

/**
 * 负责 ChatProcessor 默认系统提示词的构造，包括变量替换与知识库、记忆注入。
 */
@Component
@Slf4j
public class ChatPromptService {

    private final KnowledgeService knowledgeService;
    private final MemoryService memoryService;
    private final MemorySettings memorySettings;

    public ChatPromptService(KnowledgeService knowledgeService,
                             MemoryService memoryService,
                             MemorySettings memorySettings) {
        this.knowledgeService = knowledgeService;
        this.memoryService = memoryService;
        this.memorySettings = memorySettings;
    }

    public String defaultSystemPrompt(ChatProvider.SystemProviderContext context, AssistantSettings activeAssistantSettings, String effectiveModelName, WebSocketSession session) throws Exception {
        ChatSession chatSession = context.chatSession();
        List<ChatMessage> originMessages = context.originMessages();

        // 替换变量（系统提示词使用助手设定，模型名使用实际生效的模型）
        StringBuilder systemPrompt = new StringBuilder(activeAssistantSettings.getPrompt()
                .replace(PromptReplaceVariable.CURRENT_TIME, LocalDate.now().toString())
                .replace(PromptReplaceVariable.MODEL_NAME, effectiveModelName)
                .replace(PromptReplaceVariable.IP_ADDRESS, InetAddress.getLocalHost().toString()));

        injectKnowledgePrompt(originMessages, chatSession, systemPrompt, session);
        injectMemoryPrompt(systemPrompt);

        return systemPrompt.toString();
    }

    private void injectKnowledgePrompt(List<ChatMessage> originMessages, ChatSession chatSession, StringBuilder systemPrompt, WebSocketSession session) {
        // 知识库注入：依据用户最新消息自动挑选相关知识条目注入上下文
        try {
            ChatMessage lastUserMsg = ObjectUtils.getLast(originMessages);
            if (lastUserMsg == null) {
                return;
            }
            String queryText = lastUserMsg.resolveText();
            KnowledgeService.KnowledgeSection knowledgeResult = knowledgeService.buildKnowledgeSection(chatSession.getId(), queryText);
            if (!StringUtils.hasText(knowledgeResult.text())) {
                return;
            }
            systemPrompt.append(knowledgeResult.text());
            if (!knowledgeResult.hasNew()) {
                return;
            }
            // 控制信号通知前端：仅当本轮去重后注入了新知识条目时才推送命中信号
            session.sendMessage(new TextMessage(ControlSign.SIGN_KNOWLEDGE_HIT + chatSession.getId()));
        } catch (Exception e) {
            log.warn("知识库匹配失败: {}", e.getMessage());
        }
    }

    private void injectMemoryPrompt(StringBuilder systemPrompt) {
        // 记忆注入开关：关闭后对话不再注入记忆（不影响记忆 CRUD 与问候语个性化）
        if (!Boolean.TRUE.equals(memorySettings.getEnable())) {
            return;
        }
        try {
            String memorySection = memoryService.buildMemorySection();
            if (StringUtils.hasText(memorySection)) {
                systemPrompt.append(memorySection);
            }
        } catch (Exception e) {
            log.warn("核心记忆注入失败: {}", e.getMessage());
        }
    }
}
