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
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.plug.world.service.WorldGroupChatService;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import com.fishsunny.assistant.websocket.SynchronizedWebSocketSession;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.List;

@Slf4j
@Component("worldGroupChatSocketHandler")
public class WorldGroupChatSocketHandler extends ChatWebSocketHandler {

    private final WorldGroupChatService groupChatService;

    @Autowired
    public WorldGroupChatSocketHandler(ServiceProcessor serviceProcessor,
                                       TempChatProcessor tempChatProcessor,
                                       ChatProcessor chatProcessor,
                                       TaskExecutor chatAsyncExecutor,
                                       ObjectMapper objectMapper,
                                       SessionMessageBus sessionMessageBus,
                                       WorldGroupChatService groupChatService) {
        super(serviceProcessor, tempChatProcessor, chatProcessor, chatAsyncExecutor, objectMapper, sessionMessageBus);
        this.groupChatService = groupChatService;
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        super.chatAsyncExecutor.execute(() -> {
            // 用线程安全的包装器保护 session，防止多线程并发 sendMessage 时出现 TEXT_PARTIAL_WRITING
            final SynchronizedWebSocketSession safeSession = new SynchronizedWebSocketSession(session);
            ChatMessageRequest request = null;
            try {
                String payload = message.getPayload();

                super.replayMessage(payload, safeSession);

                request = new ChatMessageRequest().parseAndValidate(payload, super.objectMapper);

                // 创建/追加会话，落盘用户消息并推送 init_user
                ServiceProcessor.ChatSessionModeParseResult parseResult =
                        super.serviceProcessor.handleChatSession(request, safeSession, false);
                ChatSession chatSession = parseResult.chatSession();
                if (chatSession == null) {
                    throw new UserException("无效的会话 ID");
                }

                super.sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_START + chatSession.getId());

                // 群聊轮次循环：调度器选角 → 被选角色生成，一条用户消息产出多条 role=assistant 消息
                // session 用总线包装：runGroupRounds 内的 WORLD_ROUND/WORLD_POSSESS 直发改为走总线，
                // 既广播给该会话所有订阅连接，又写入轮次缓冲供断线重连续传重建角色气泡
                WebSocketSession busSession = super.sessionMessageBus.wrap(safeSession, chatSession.getId());
                groupChatService.runGroupRounds(chatSession, busSession);

                if (parseResult.isNewChat()) {
                    super.serviceProcessor.generateTitle(chatSession, request.getContent(), "");
                }

                super.sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_END + chatSession.getId());
            } catch (UserException e) {
                log.warn(e.getMessage());
                super.sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, e.getMessage());
            } catch (Exception e) {
                log.error("群聊对话异常: {}", e.getMessage(), e);
                super.sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, "群聊异常: " + e.getMessage());
            }
        });
    }
}
