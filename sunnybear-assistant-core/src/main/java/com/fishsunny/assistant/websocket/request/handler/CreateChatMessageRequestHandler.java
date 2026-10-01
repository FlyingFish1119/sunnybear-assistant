package com.fishsunny.assistant.websocket.request.handler;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:33
 */

import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.jev.JevClient;
import com.fishsunny.assistant.engine.jev.request.JevRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.mvc.service.CronJobService;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.websocket.request.*;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import com.fishsunny.assistant.websocket.ws.SynchronizedWebSocketSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateChatMessageRequestHandler implements ChatMessageRequestHandler {

    private final ChatSessionService chatSessionService;
    private final CronJobService cronJobService;
    private final SessionFileWriter sessionFileWriter;
    private final UserMessageAppender userMessageAppender;
    private final UserSettings userSettings;
    private final SessionMessageBus sessionMessageBus;
    private final JevClient jevClient;

    @Override
    public ChatSessionModeParseResult handle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception {
        ChatSession chatSession;
        // cron 触发：通过 cronId 查库获取标题，session type = 'cron'
        if (request.getCronId() != null) {
            chatSession = createCronChatSession(request.getCronId());
        } else {
            boolean enablePro = false;
            if (Boolean.TRUE.equals(provider.getEnableProModel().get())) {
                enablePro = judgeProModel(request.getContent());
            }
                chatSession = createChatSession(request, enablePro, provider.getSessionType().get());
        }
        sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
        request.setSessionId(chatSession.getId());
        // 先写文件，再创建带文件引用的用户消息
        List<String> createFileUrls = sessionFileWriter.writeSessionFile(request.getFiles(), chatSession);
        ChatMessage newMessage = userMessageAppender.appendUserMessage(chatSession.getId(), null, request.getContent(), createFileUrls);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(newMessage);
        return new ChatSessionModeParseResult(chatSession, true, messages);
    }

    @Override
    public String getMode() {
        return ChatMessageRequest.MODE_CREATE;
    }

    private ChatSession createChatSession(ChatMessageRequest request, boolean enablePro, String sessionType) throws Exception {
        ChatSession chatSession = new ChatSession("新会话");
        chatSession.setEnablePro(enablePro);
        if (StringUtils.hasText(sessionType)) {
            chatSession.setType(sessionType);
        }
        if (!CollectionUtils.isEmpty(request.getExtension())) {
            chatSession.setExtension(new java.util.LinkedHashMap<>(request.getExtension()));
        }
        try {
            return chatSessionService.save(chatSession);
        } catch (Exception e) {
            log.error("Error create chat session: {}", e.getMessage());
            throw new UserException("创建会话失败: " + e.getMessage());
        }
    }

    /**
     * 为 cron 定时任务创建会话，标题为 "cron任务标题_时间戳"
     * @param cronId cron 任务 ID
     */
    private ChatSession createCronChatSession(Integer cronId) throws Exception {
        CronJob cronJob = cronJobService.findById(cronId);
        if (cronJob == null) {
            throw new UserException("cron 任务不存在: " + cronId);
        }
        String name = cronJob.getTitle() + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        ChatSession chatSession = new ChatSession(name);
        chatSession.setType("cron");
        chatSession.setEnablePro(cronJob.getEnablePro() != null && cronJob.getEnablePro());
        chatSession.setUnreviewed(cronJob.getUnreviewed() != null && cronJob.getUnreviewed());
        try {
            return chatSessionService.save(chatSession);
        } catch (Exception e) {
            log.error("Error create cron chat session: {}", e.getMessage());
            throw new UserException("创建 cron 会话失败: " + e.getMessage());
        }
    }

    private boolean judgeProModel(String userQuestion) {
        if (userSettings.getEnableAutoSwitchModel() == null || !userSettings.getEnableAutoSwitchModel()) {
            return false;
        }
        if (!StringUtils.hasText(userQuestion)) {
            return false;
        }
        try {
            JevRequest jevRequest = JevRequest.Builder.create(userQuestion)
                    .noulQuestion(
                            "complex_question",
                            "Is the user's question a complex question, or one that will lead to a complex task?",
                            "Questions that are inherently complex (deep reasoning, multi-step logic, domain expertise, complex code analysis), or that will likely spawn a subsequent complex task (multi-file code changes, system-level operations, long multi-step workflows).",
                            "Casual greetings, simple factual Q&A, basic translation, format conversion, or other questions that are neither complex themselves nor likely to lead to a complex task."
                    )
                    .build();
            Double needProModel = jevClient.send(jevRequest).mappingNoul("complex_question");
            return needProModel != null && needProModel > userSettings.getProModelThreshold();
        } catch (Exception e) {
            log.warn("模型复杂度判断失败，默认使用标准模型: {}", e.getMessage());
            return false;
        }
    }
}
