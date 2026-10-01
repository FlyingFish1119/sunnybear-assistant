package com.fishsunny.assistant.websocket.request.handler;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 20:06
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import com.fishsunny.assistant.websocket.ws.SynchronizedWebSocketSession;
import com.fishsunny.assistant.websocket.request.ChatMessageRequestHandler;
import com.fishsunny.assistant.websocket.request.ChatMessageRequestProvider;
import com.fishsunny.assistant.websocket.request.ChatSessionModeParseResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
public class EditChatMessageRequestHandler implements ChatMessageRequestHandler {

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final UserSettings userSettings;
    private final ObjectMapper objectMapper;
    private final SessionMessageBus sessionMessageBus;

    @Override
    public ChatSessionModeParseResult handle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception {
        ChatSession chatSession = chatSessionService.findById(request.getSessionId());
        if (chatSession == null) {
            throw new UserException("会话不存在: " + request.getSessionId());
        }
        sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
        List<ChatMessage> messages = handleEdit(request);
        return new ChatSessionModeParseResult(chatSession, false, messages);
    }

    @Override
    public String getMode() {
        return ChatMessageRequest.MODE_EDIT;
    }

    /**
     * 处理 edit 模式：停用旧用户消息分支，创建新的用户消息，返回历史消息
     * <p>仅替换文本内容，保留旧消息中的文件附件（image / video / audio / file）
     */
    private List<ChatMessage> handleEdit(ChatMessageRequest request) throws Exception {
        String editMessageId = request.getEditMessageId();
        if (!StringUtils.hasText(editMessageId)) {
            throw new UserException("edit 模式下 editMessageId 不能为空");
        }

        // 找到要被编辑的用户消息
        ChatMessage oldUserMsg = chatMessageService.findById(editMessageId);
        if (oldUserMsg == null) {
            throw new UserException("要编辑的消息不存在: " + editMessageId);
        }
        if (!ChatMessage.ROLE_USER.equals(oldUserMsg.getRole())) {
            throw new UserException("只能编辑用户消息，当前消息角色为: " + oldUserMsg.getRole());
        }

        // 编辑只替换正文（首块）：首块之后的全部内容原样带入新消息，
        // 包括文件附件与只作展示、不参与编辑的追加文本块
        List<MessageContent> preservedContents = new ArrayList<>();
        List<MessageContent> oldContents = oldUserMsg.getContents();
        if (oldContents != null && oldContents.size() > 1) {
            preservedContents.addAll(oldContents.subList(1, oldContents.size()));
        }

        // 获取旧用户消息的 parentId（可能为 null，表示根消息）
        String parentId = oldUserMsg.getParentId();

        // 停用旧的用户消息分支（包括所有子孙消息）
        chatMessageService.deactivateBranch(editMessageId);

        ChatMessage chatMessage = new ChatMessage()
                .user(request.getContent(), preservedContents)
                .makeInsertable(request.getSessionId(), parentId, userSettings.getUsername());

        try {
            ChatMessage message = chatMessageService.save(chatMessage);
            ChatResponse response = new ChatResponse()
                    .setMessages(List.of(message))
                    .setStatus(ChatResponse.STATUS_INIT_USER)
                    .setSessionId(request.getSessionId());
            sessionMessageBus.publish(request.getSessionId(), objectMapper.writeValueAsString(response));
        } catch (UserException e) {
            throw new UserException("保存用户消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存用户消息失败: " + e.getMessage());
        }

        // 返回当前活跃的历史消息（包括新创建的用户消息）
        return chatMessageService.getConversationHistory(request.getSessionId());
    }
}
