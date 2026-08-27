package com.fishsunny.assistant.plug.world.websocket;

/*
 * @Usage 世界观群聊 WebSocket 处理器 —— 复用单聊引擎的会话/消息管线，重写 handleTextMessage 跑群聊轮次循环
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.plug.world.service.WorldGroupChatService;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.SynchronizedWebSocketSession;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@Component
public class WorldGroupChatSocketHandler extends ChatWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(WorldGroupChatSocketHandler.class);

    private final ServiceProcessor serviceProcessor;
    private final WorldGroupChatService groupChatService;
    private final ObjectMapper objectMapper;
    private final TaskExecutor chatAsyncExecutor;

    @Autowired
    public WorldGroupChatSocketHandler(ServiceProcessor serviceProcessor,
                                       TempChatProcessor tempChatProcessor,
                                       ChatProcessor chatProcessor,
                                       TaskExecutor chatAsyncExecutor,
                                       ObjectMapper objectMapper,
                                       WorldGroupChatService groupChatService) {
        super(serviceProcessor, tempChatProcessor, chatProcessor, chatAsyncExecutor, objectMapper);
        this.serviceProcessor = serviceProcessor;
        this.groupChatService = groupChatService;
        this.objectMapper = objectMapper;
        this.chatAsyncExecutor = chatAsyncExecutor;
    }

    @Override
    protected boolean isProModelEnabled() {
        // 群聊按角色自己的 ai_settings 走模型，不做 pro 自动切换
        return false;
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        chatAsyncExecutor.execute(() -> {
            // 用线程安全的包装器保护 session，防止多线程并发 sendMessage 时出现 TEXT_PARTIAL_WRITING
            final WebSocketSession safeSession = new SynchronizedWebSocketSession(session);
            ChatMessageRequest request = null;
            try {
                String payload = message.getPayload();
                request = new ChatMessageRequest().parseAndValidate(payload, objectMapper);

                // 创建/追加会话，落盘用户消息并推送 init_user
                ServiceProcessor.ChatSessionModeParseResult parseResult =
                        serviceProcessor.handleChatSession(request, safeSession, false);
                ChatSession chatSession = parseResult.chatSession();
                if (chatSession == null) {
                    throw new UserException("无效的会话 ID");
                }

                safeSession.sendMessage(new TextMessage(ControlSign.SIGN_START + chatSession.getId()));

                // 群聊轮次循环：调度器选角 → 被选角色生成，一条用户消息产出多条 role=assistant 消息
                groupChatService.runGroupRounds(chatSession, safeSession);

                if (parseResult.isNewChat()) {
                    serviceProcessor.generateTitle(safeSession, chatSession, request.getContent(), "");
                }

                safeSession.sendMessage(new TextMessage(ControlSign.SIGN_END + chatSession.getId()));
            } catch (UserException e) {
                log.warn(e.getMessage());
                sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, e.getMessage());
            } catch (Exception e) {
                log.error("群聊对话异常: {}", e.getMessage(), e);
                sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, "群聊异常: " + e.getMessage());
            }
        });
    }

    private void sendErrorToFrontend(WebSocketSession safeSession, String sessionId, String errorMessage) {
        try {
            ChatResponse errorResp = new ChatResponse().afterError(sessionId != null ? sessionId : "", errorMessage);
            safeSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResp)));
        } catch (Exception ignored) {
            log.warn("发送错误信息到前端失败，连接可能已断开");
        }
    }
}
