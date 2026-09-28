package com.fishsunny.assistant.websocket.processor.request;

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;

import java.util.List;

public record ChatSessionModeParseResult(ChatSession chatSession, boolean isNewChat, List<ChatMessage> messages) {}