package com.fishsunny.assistant.websocket.processor.slash;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/19 09:11
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.websocket.ChatProvider;
import lombok.Getter;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public abstract class SlashCommandExecutor {

    protected String command;

    protected List<String> args;

    protected WebSocketSession session;

    protected ChatSession chatSession;

    protected List<ChatMessage> originMessages;

    protected abstract SlashCommand resolve(String command);

    protected abstract List<ChatMessage> handle();

    public final boolean isSlashCommand(String originCommand) {
        Pattern pattern = Pattern.compile("^/[a-zA-Z]+");
        return pattern.matcher(originCommand).matches();
    }

    public final List<ChatMessage> run(SlashCommandContext context) {
        if (!isSlashCommand(context.originCommand())) {
            throw new IllegalArgumentException("Invalid slash command");
        }
        this.chatSession = context.chatSession();
        this.originMessages = context.originMessages();
        SlashCommand slashCommand = resolve(context.originCommand());
        this.command = slashCommand.command();
        this.args = slashCommand.args();
        return handle();
    }


    protected record SlashCommand(String command, List<String> args) {
    }

    public record SlashCommandContext(String originCommand, WebSocketSession session, ChatSession chatSession, List<ChatMessage> originMessages) { }
}
