package com.fishsunny.assistant.websocket.processor.slash.framwork;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/19 09:11
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

public abstract class SlashCommandHandler {

    protected static final Logger log = LoggerFactory.getLogger(SlashCommandHandler.class);

    protected String command;

    protected List<String> args;

    protected WebSocketSession session;

    protected ChatSession chatSession;

    protected List<ChatMessage> originMessages;

    protected List<ChatMessage> resultMessage;

    protected abstract SlashCommand resolve(String command);

    protected abstract void handle() throws Exception;

    protected void insertMessage(ChatMessage chatMessage, ChatMessageService chatMessageService) {
        try {
            chatMessageService.save(chatMessage);
        } catch (Exception e) {
            throw new RuntimeException("保存助手消息失败，系统发生了未知错误: " + e.getMessage());
        }
    }

    protected void sendMessage(ChatMessage chatMessage, ObjectMapper objectMapper) {
        ChatResponse chatResponse = new ChatResponse().afterAIResponse(chatMessage);
        try {
            this.session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatResponse)));
        } catch (Exception e) {
            log.error("发送助手消息失败: {}", e.getMessage());
        }
    }

    public final void run(SlashCommandContext context) throws Exception {
        SlashCommand slashCommand = resolve(context.originCommand());
        this.command = slashCommand.command();
        this.args = slashCommand.args();

        this.chatSession = context.chatSession();
        this.originMessages = context.originMessages();
        this.session = context.session();
        this.resultMessage = context.resultMessage();
        handle();
    }


    protected record SlashCommand(String command, List<String> args) {
    }

    public record SlashCommandContext(
            String originCommand,
            WebSocketSession session,
            ChatSession chatSession,
            List<ChatMessage> originMessages,
            List<ChatMessage> resultMessage
    ) { }
}
