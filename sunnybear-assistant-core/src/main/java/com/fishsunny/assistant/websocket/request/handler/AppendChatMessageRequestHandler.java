package com.fishsunny.assistant.websocket.request.handler;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:56
 */

import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.websocket.request.*;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import com.fishsunny.assistant.websocket.ws.SynchronizedWebSocketSession;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AppendChatMessageRequestHandler extends ChatMessageRequestHandler {

    private final UserMessageAppender userMessageAppender;
    private final ChatMessageService chatMessageService;
    private final SessionMessageBus sessionMessageBus;
    private final SessionFileWriter sessionFileWriter;

    public AppendChatMessageRequestHandler(
            UserMessageAppender userMessageAppender,
            ChatMessageService chatMessageService,
            SessionMessageBus sessionMessageBus,
            SessionFileWriter sessionFileWriter,
            ChatSessionService chatSessionService
    ) {
        super(chatSessionService);
        this.userMessageAppender = userMessageAppender;
        this.chatMessageService = chatMessageService;
        this.sessionMessageBus = sessionMessageBus;
        this.sessionFileWriter = sessionFileWriter;
    }

    @Override
    public ChatSessionModeParseResult doHandle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception {
        ChatSession chatSession = super.chatSessionService.findById(request.getSessionId());
        if (chatSession == null) {
            throw new UserException("会话不存在: " + request.getSessionId());
        }
        sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
        List<ChatMessage> messages = chatMessageService.getConversationHistory(request.getSessionId());
        ChatMessage last = ObjectUtils.getLast(messages);
        String parentId = last == null ? null : last.getId();
        List<String> appendFileUrls = sessionFileWriter.writeSessionFile(request.getFiles(), chatSession);
        ChatMessage newMessage = userMessageAppender.appendUserMessage(request.getSessionId(), parentId, request.getContent(),
                appendFileUrls, null, request.getMessageExtension());
        messages.add(newMessage);
        return new ChatSessionModeParseResult(chatSession, false, messages);
    }

    @Override
    public String getMode() {
        return ChatMessageRequest.MODE_APPEND;
    }
}
