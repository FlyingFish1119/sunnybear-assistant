package com.fishsunny.assistant.websocket;

/*
 * @Usage WebSocket 对话处理器 —— 薄层编排器，负责解析、校验、分发
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.controller.ChatController;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


@Slf4j
@Primary
@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {

    protected final ServiceProcessor serviceProcessor;
    protected final TempChatProcessor tempChatProcessor;
    protected final ChatProcessor chatProcessor;
    protected final TaskExecutor chatAsyncExecutor;
    protected final ObjectMapper objectMapper;

    /**
     * 记录每个连接的活跃异步任务数
     */
    protected final Map<String, Integer> activeTaskCount = new ConcurrentHashMap<>();

    /**
     * 会话消息总线：消息按 chatSessionId 发布并广播给订阅连接，重连连接可订阅续传
     */
    protected final SessionMessageBus sessionMessageBus;

    public ChatWebSocketHandler(ServiceProcessor serviceProcessor,
                                TempChatProcessor tempChatProcessor,
                                ChatProcessor chatProcessor,
                                TaskExecutor chatAsyncExecutor,
                                ObjectMapper objectMapper,
                                SessionMessageBus sessionMessageBus) {
        this.serviceProcessor = serviceProcessor;
        this.tempChatProcessor = tempChatProcessor;
        this.chatProcessor = chatProcessor;
        this.chatAsyncExecutor = chatAsyncExecutor;
        this.objectMapper = objectMapper;
        this.sessionMessageBus = sessionMessageBus;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        log.info("WebSocket 连接已建立: {}", session.getId());
        activeTaskCount.put(session.getId(), 0);
    }

    /**
     * 公共桥接方法，供 WebSocket 客户端复用完整对话流程。
     */
    public void processMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        handleTextMessage(session, message);
    }

    /**
     * 会话建立完成后构建真正用于本轮对话的 ChatProvider。
     * 默认使用 {@link ChatProvider#DEFAULT}；需要按会话解析绑定数据（角色/世界）的插件可重写本方法以挂载 per-session 设置。
     */
    public ChatProvider chatToAiProvider(ChatSession chatSession) {
        return ChatProvider.DEFAULT;
    }

    /**
     * 新建会话（MODE_CREATE）时是否允许自动切换到高级模型（pro）。
     * 普通对话默认允许；绑定固定模型的插件端点（如角色）应重写为 false。
     */
    public boolean enableSwitchPro() {
        return true;
    }

    /**
     * 本连接端点归属的会话类型：新建会话（MODE_CREATE）时用于给 ChatSession.type 打标，
     * 使插件会话（角色/世界）只需 create 携带 extension 即可一次性完成绑定注册。
     */
    public String sessionType() {
        return "chat";
    }

    /**
     * 额外的信号解析 Hook。子类可重写此方法以拦截并处理特定信号。
     * 在正常的聊天流程之前调用。
     *
     * @param session WebSocket 会话
     * @param payload 原始消息文本
     * @return true = 信号已被处理，跳过正常聊天流程；false = 继续正常聊天流程
     */
    protected boolean handleAdditionalSignal(WebSocketSession session, String payload) {
        return false;
    }

    protected final boolean replayMessage(String payload, SynchronizedWebSocketSession safeSession) throws Exception {
        if (payload.startsWith(ControlSign.SIGN_REQUIRE_REPLAY_MESSAGE)) {
            String sessionId = payload.substring(ControlSign.SIGN_REQUIRE_REPLAY_MESSAGE.length());
            // 独占订阅会话总线：返回当前进行中一轮的缓存快照，之后的新消息实时广播到此连接
            // 订阅身份统一用 delegate()（原始连接），与 handleChatSession 的订阅一致，
            // 避免包装器与原始连接同时出现在订阅集合导致同一条消息被推送两次
            List<SessionMessageBus.Event> replayEvents = sessionMessageBus.subscribeExclusive(sessionId, safeSession.delegate());
            if (CollectionUtils.isEmpty(replayEvents)) {
                return true;
            }
            log.info("会话 [{}] 订阅总线，回放 {} 条消息: {}", safeSession.getId(), replayEvents.size(), sessionId);

            // 交互信号（TOOL_ASK 工具确认 / TOOL_QUESTION 结构化提问）只重放"仍挂在服务端等用户应答"的那些：
            // 二者发布后，工具各自阻塞在 ChatController.awaitConfirm / awaitQuestion 直到用户应答，随后才被清理。
            // 直接以 ChatController 的 pending 表为准判断（id 是否仍在表内 = 是否仍待应答），不依赖缓冲区的
            // 相对顺序——同一轮并行执行的其它工具无论在其前/其后发布事件都不影响判断，也不怕"已应答但轮次
            // 还没推进"的快照形态。已被应答/超时/清理的 ask/question 若再重放，会弹出已无实际作用的确认/提问框。
            // 其余普通事件帧照常重放，用于重建在途消息。
            synchronized (safeSession.delegate()) {
                safeSession.sendMessage(new TextMessage(ControlSign.SIGN_REPLAY_MESSAGE + sessionId));
                for (SessionMessageBus.Event event : replayEvents) {
                    if (!shouldReplay(sessionId, event.payload())) {
                        continue;
                    }
                    safeSession.sendMessage(new TextMessage(event.payload()));
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 该事件帧是否应重放给重连连接。交互信号按 pending 表过滤；其余一律重放。
     * 供子类重写扩展：子类先调用 super，再对插件自己的交互信号（如角色战斗回合）追加过滤，
     * sessionId 用于按会话键控的 pending 查询（如 BattleController 以 sessionId 为键）。
     */
    protected boolean shouldReplay(String sessionId, String eventPayload) {
        String sign = null;
        if (eventPayload.startsWith(ControlSign.SIGN_TOOL_ASK)) {
            sign = ControlSign.SIGN_TOOL_ASK;
        } else if (eventPayload.startsWith(ControlSign.SIGN_TOOL_QUESTION)) {
            sign = ControlSign.SIGN_TOOL_QUESTION;
        } else {
            return true;
        }
        String id = extractInteractionId(eventPayload, sign);
        if (id == null || id.isEmpty()) {
            // 解析不到 id 时兜底按"仍待应答"重放，避免漏掉真实待确认的请求
            return true;
        }
        return ControlSign.SIGN_TOOL_ASK.equals(sign)
                ? ChatController.isConfirmPending(id)
                : ChatController.isQuestionPending(id);
    }

    /** 从交互信号帧（sign + json）中解析出唯一 id（ToolAsk / ToolQuestion 均带 id 字段） */
    private String extractInteractionId(String eventPayload, String sign) {
        try {
            JsonNode node = objectMapper.readTree(eventPayload.substring(sign.length()));
            return node.hasNonNull("id") ? node.get("id").asText() : null;
        } catch (Exception e) {
            log.warn("解析交互信号 {} 的 id 失败，按待重放处理: {}", sign, e.getMessage());
            return null;
        }
    }

    @Override
    protected void handleTextMessage(@NonNull WebSocketSession session, @NonNull TextMessage message) {
        chatAsyncExecutor.execute(() -> {
            // 用线程安全的包装器保护 session，防止多线程并发 sendMessage 时出现 TEXT_PARTIAL_WRITING
            final SynchronizedWebSocketSession safeSession = new SynchronizedWebSocketSession(session);

            // 提升到 try 外：错误处理时需要回传 sessionId，让前端能按会话清理流式状态
            ChatMessageRequest request = null;
            ChatSession chatSession = null;
            try {
                String payload = message.getPayload();

                if (replayMessage(payload, safeSession)) {
                    return;
                }

                // 让子类有机会拦截并处理特定信号（如战斗回合行动）
                if (handleAdditionalSignal(safeSession, payload)) {
                    return;
                }

                // 检查请求
                request = new ChatMessageRequest().parseAndValidate(payload, objectMapper);

                if (request.isTemp()) {
                    tempChatProcessor.chat(request, safeSession);
                    return;
                }

                // 处理会话
                ServiceProcessor.ChatSessionModeParseResult parseResult = serviceProcessor.handleChatSession(request, safeSession, enableSwitchPro(), sessionType());

                // 处理请求
                chatSession = parseResult.chatSession();

                if (chatSession == null) {
                    throw new UserException("无效的会话 ID");
                }

                try {
                    activeTaskCount.merge(safeSession.getId(), 1, Integer::sum);

                    sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_START + chatSession.getId());

                    WebSocketSession busSession = sessionMessageBus.wrap(safeSession, chatSession.getId());

                    List<ChatMessage> chatMessages = chatProcessor.chatToAi(parseResult.messages(), chatSession, busSession, chatToAiProvider(chatSession));

                    // 如果是新的会话并且不是定时任务，则生成标题
                    if (parseResult.isNewChat() && request.getCronId() == null) {
                        serviceProcessor.generateTitle(chatSession, request.getContent(), chatMessages.getFirst().resolveText());
                    }

                    sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_END + chatSession.getId());
                } catch (IOException e) {
                    log.warn("WebSocket 发送失败，连接可能已关闭 [{}]: {}", safeSession.getId(), e.getMessage());
                } catch (Exception e) {
                    log.error("chatToAi async error [{}]: {}", safeSession.getId(), e.getMessage(), e);
                    sendErrorToFrontend(safeSession, chatSession.getId(), "AI 对话异常: " + e.getMessage());
                } finally {
                    activeTaskCount.merge(safeSession.getId(), -1, Integer::sum);
                }

            } catch (UserException e) {
                log.warn(e.getMessage(), e);
                sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, e.getMessage());
            } catch (Exception e) {
                log.error("error: {}", e.getMessage(), e);
                sendErrorToFrontend(safeSession, request != null ? request.getSessionId() : null, "系统内部错误，请稍后重试");
            }
        });
    }


    /** 将错误信息通过 ChatResponse 推送到前端 */
    protected void sendErrorToFrontend(WebSocketSession safeSession, String sessionId, String errorMessage) {
        try {
            ChatResponse errorResp = new ChatResponse().afterError(sessionId != null ? sessionId : "", errorMessage);
            safeSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(errorResp)));
        } catch (Exception ignored) {
            log.warn("发送错误信息到前端失败，连接可能已断开");
        }
        // 本轮异常中止：清空总线上的当前轮缓存，避免残留半截事件影响后续订阅
        if (sessionId != null) {
            sessionMessageBus.reset(sessionId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, @NonNull CloseStatus status) {
        log.info("WebSocket 连接已关闭: {}, 状态: {}, 未完成任务数: {}",
                session.getId(), status, activeTaskCount.getOrDefault(session.getId(), 0));
        activeTaskCount.remove(session.getId());
        sessionMessageBus.unsubscribeAll(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("WebSocket 传输错误 [{}]: {}", session.getId(), exception.getMessage());
        activeTaskCount.remove(session.getId());
        sessionMessageBus.unsubscribeAll(session);
        super.handleTransportError(session, exception);
    }
}
