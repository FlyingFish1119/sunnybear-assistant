package com.fishsunny.assistant.websocket.processor.request.handler;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 20:02
 */

import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import com.fishsunny.assistant.websocket.SynchronizedWebSocketSession;
import com.fishsunny.assistant.websocket.processor.request.ChatMessageRequestHandler;
import com.fishsunny.assistant.websocket.processor.request.ChatMessageRequestProvider;
import com.fishsunny.assistant.websocket.processor.request.ChatSessionModeParseResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReplaceChatMessageRequestHandler implements ChatMessageRequestHandler {

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final SessionMessageBus sessionMessageBus;

    @Override
    public ChatSessionModeParseResult handle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception {
        ChatSession chatSession = chatSessionService.findById(request.getSessionId());
        if (chatSession == null) {
            throw new UserException("会话不存在: " + request.getSessionId());
        }
        sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
        List<ChatMessage> messages = handleReplace(request);
        sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_REPLACE + request.getReplaceMessageId());
        return new ChatSessionModeParseResult(chatSession, false, messages);
    }

    @Override
    public String getMode() {
        return ChatMessageRequest.MODE_REPLACE;
    }

    /**
     * 处理 replace 模式：停用旧助手分支，返回历史消息到父用户消息为止
     */
    private List<ChatMessage> handleReplace(ChatMessageRequest request) throws Exception {
        String replaceMessageId = request.getReplaceMessageId();
        if (!StringUtils.hasText(replaceMessageId)) {
            throw new UserException("replace 模式下 replaceMessageId 不能为空");
        }

        // 找到要被替换的助手消息
        ChatMessage replacedMsg = chatMessageService.findById(replaceMessageId);
        if (replacedMsg == null) {
            throw new UserException("要替换的消息不存在: " + replaceMessageId);
        }
        if (!ChatMessage.ROLE_ASSISTANT.equals(replacedMsg.getRole())) {
            throw new UserException("只能替换助手消息，当前消息角色为: " + replacedMsg.getRole());
        }

        // 验证父消息必须是用户消息
        String parentId = replacedMsg.getParentId();
        if (!StringUtils.hasText(parentId)) {
            throw new UserException("要替换的消息没有父消息");
        }
        ChatMessage parentMsg = chatMessageService.findById(parentId);
        if (parentMsg == null || (!ChatMessage.ROLE_USER.equals(parentMsg.getRole()) && !ChatMessage.ROLE_TOOL.equals(parentMsg.getRole()))) {
            throw new UserException("被替换消息的父消息必须是用户消息或工具消息");
        }

        // 停用旧的助手分支（包括所有子孙消息）
        chatMessageService.deactivateBranch(replaceMessageId);

        // 返回当前活跃的历史消息（旧分支已停用，历史会截止到父用户消息）
        return chatMessageService.getConversationHistory(request.getSessionId());
    }
}
