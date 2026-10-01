package com.fishsunny.assistant.websocket.request;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.List;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:38
 */

@Component
public class UserMessageAppender {

    private final ChatMessageService chatMessageService;
    private final UserSettings userSettings;
    private final SessionMessageBus sessionMessageBus;
    private final BackgroundToolResponseBus backgroundToolResponseBus;
    private final ObjectMapper objectMapper;


    public UserMessageAppender(ChatMessageService chatMessageService,
                               UserSettings userSettings,
                               SessionMessageBus sessionMessageBus,
                               BackgroundToolResponseBus backgroundToolResponseBus,
                               ObjectMapper objectMapper) {
        this.chatMessageService = chatMessageService;
        this.userSettings = userSettings;
        this.sessionMessageBus = sessionMessageBus;
        this.backgroundToolResponseBus = backgroundToolResponseBus;
        this.objectMapper = objectMapper;
    }

    /**
     * 添加用户消息（含文件附件）
     *
     * @param sessionId 会话 ID
     * @param parentId  父消息 ID
     * @param prompt    用户输入的文本
     * @param fileUrls  文件引用列表（形如 "sessionId:fileName"），用于前端通过 /file/proxy 获取
     */
    public ChatMessage appendUserMessage(String sessionId,
                                          String parentId,
                                          String prompt,
                                          List<String> fileUrls) throws Exception {
        if (StringUtils.hasText(parentId)) {
            // 父消息是工具消息，则插入一个空的助手消息作为占位符
            ChatMessage lastMessage = chatMessageService.findById(parentId);
            if (ChatMessage.ROLE_TOOL.equals(lastMessage.getRole())) {
                ChatMessage paddingAssistant = new ChatMessage()
                        .assistant("", "", List.of())
                        .makeInsertable(sessionId, lastMessage.getParentId(), userSettings.getUsername());
                ChatMessage saved = chatMessageService.save(paddingAssistant);
                parentId = saved.getId();
            }
            // 父消息是助手消息且包含工具调用，则移除工具调用
            if (ChatMessage.ROLE_ASSISTANT.equals(lastMessage.getRole()) && !CollectionUtils.isEmpty(lastMessage.getToolCalls())) {
                ChatMessage fixAssistant = new ChatMessage();
                BeanUtils.copyProperties(lastMessage, fixAssistant);
                fixAssistant.setToolCalls(List.of());
                chatMessageService.replace(fixAssistant);
            }
        }
        List<MessageContent> fileContents = MessageContent.files(fileUrls);
        ChatMessage chatMessage = new ChatMessage()
                .user(prompt, fileContents)
                .makeInsertable(sessionId, parentId, userSettings.getUsername());
        // 落库前探一次后台任务总线：上一轮遗留的在途结果作为追加文本块挂到这条用户消息末尾
        backgroundToolResponseBus.attachPending(chatMessage);

        try {
            ChatMessage message = chatMessageService.save(chatMessage);
            ChatResponse response = new ChatResponse().afterUserInput(message);
            sessionMessageBus.publish(sessionId, objectMapper.writeValueAsString(response));
            return message;
        } catch (UserException e) {
            throw new UserException("保存用户消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存用户消息失败: " + e.getMessage());
        }
    }
}
