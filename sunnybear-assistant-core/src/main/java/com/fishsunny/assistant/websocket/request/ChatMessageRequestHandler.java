package com.fishsunny.assistant.websocket.request;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:29
 */

import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.websocket.ws.SynchronizedWebSocketSession;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class ChatMessageRequestHandler {

    protected final ChatSessionService chatSessionService;

    public ChatMessageRequestHandler(ChatSessionService chatSessionService) {
        this.chatSessionService = chatSessionService;
    }

    /**
     * 模板方法：子类只负责解析与落消息，活跃时间的收尾统一放在这里。
     * <p>会话只要处理了一条 WS 消息就算活跃，刷新 update_time 让它排到会话列表最前。
     * 不能指望 token 累计那条路 —— mergeExtension 刻意不动 update_time，模型没回 usage 时就会漏。
     */
    public ChatSessionModeParseResult handle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception {
        ChatSessionModeParseResult result = doHandle(request, safeSession, provider);
        touchUpdateTime(result);
        return result;
    }

    /** 刷新会话活跃时间：失败只告警，不能因为记时间影响消息处理本身 */
    private void touchUpdateTime(ChatSessionModeParseResult result) {
        if (result == null || result.chatSession() == null) {
            return;
        }
        String sessionId = result.chatSession().getId();
        try {
            chatSessionService.touchUpdateTime(sessionId);
        } catch (Exception e) {
            log.warn("刷新会话活跃时间失败 [{}]: {}", sessionId, e.getMessage());
        }
    }

    protected abstract ChatSessionModeParseResult doHandle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception;

    public abstract String getMode();
}
