package com.fishsunny.assistant.utils;

/*
 * @Usage 工具执行状态推送器 —— 借助 ToolExecutor 的 beforeExec / afterExec 两个 hook，
 *        将工具执行状态实时推送到前端：
 *          beforeExec → tool_execution 黄色占位（前端展示"执行中…"黄点）
 *          afterExec  → tool_response 真实结果（前端按工具名替换占位）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

@Slf4j
public final class ToolExecuteNotifier {

    private ToolExecuteNotifier() {
    }

    /**
     * 构建推送用的 ToolProvider。
     *
     * @param session         WebSocket 会话，为空时不推送（返回 null）
     * @param chatSessionId   前端用于匹配当前会话的 sessionId
     * @param objectMapper    JSON 序列化器
     * @return 构建好的 provider；无法推送时返回 null（调用方按无 hook 处理）
     */
    public static ToolExecutor.ToolProvider buildProvider(WebSocketSession session, String chatSessionId, ObjectMapper objectMapper) {
        if (session == null || chatSessionId == null) {
            return null;
        }

        // 每个工具名的待配对"假 toolCallId"队列：beforeExec 入队、afterExec 出队。
        // 占位消息与结果消息共用同一个假 id（前端不操作工具消息，id 真假无所谓），
        // 前端据此按 toolCallId 替换占位，与真实 DB 消息 id 无关。
        String fakeId = UUID.randomUUID().toString();

        Consumer<ToolExecutor.ToolRequest> beforeExec = request -> {
            try {
                String toolName = request.getToolName();
                ChatMessage placeholder = new ChatMessage()
                        .setId(UUID.randomUUID().toString())
                        .tool(request.getToolCallId(), "")
                        .makeInsertable(chatSessionId, null, toolName)
                        .setCreateTime(LocalDateTime.now());
                placeholder.setExtension(Map.of("status", "executing"));
                ChatResponse response = new ChatResponse()
                        .afterToolExecution(List.of(placeholder))
                        .setSessionId(chatSessionId);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
            } catch (Exception e) {
                log.warn("推送工具执行状态失败: {}", e.getMessage());
            }
        };

        Consumer<ToolExecutor.ToolExecuteResponse> afterExec = response -> {
            try {
                String toolName = response.getName();
                // 携带多模态 content 分片：工具结果含图片/音频时，实时 tool_response 帧即带上该分片，
                // 前端 tool 气泡可立刻渲染媒体，无需等待 init_tool 落库帧
                ChatMessage resultMsg = new ChatMessage()
                        .setId(UUID.randomUUID().toString())
                        .tool(response.getToolCallId(), objectMapper.writeValueAsString(response),
                                MessageContent.toMessageContents(response.getMultimodalContents()))
                        .makeInsertable(chatSessionId, null, toolName)
                        .setCreateTime(LocalDateTime.now());
                ChatResponse push = new ChatResponse().afterToolCall(List.of(resultMsg));
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(push)));
            } catch (Exception e) {
                log.warn("推送工具结果失败: {}", e.getMessage());
            }
        };

        return new ToolExecutor.ToolProvider(beforeExec, afterExec);
    }
}
