package com.fishsunny.assistant.engine.protocol.project.processor;

/*
 * @Usage 工具调用循环 - 轻量 ReAct agent loop，给定 ChatRequest 执行 AI 工具调用循环，返回最终文本
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/24
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.protocol.project.AgentLogEntry;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.constants.ControlSign;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 轻量 ReAct 工具调用循环。
 * 给定 ChatRequest（含 system prompt、user prompt、工具定义）和 AISettings，
 * 执行 AI → 工具调用 → AI → ... 的递归循环，直到 AI 返回纯文本为止。
 * <p>
 * 无状态、无持久化——只做一件事：跑完工具调用循环，返回最终结果。
 */
@Component
public class ToolCallLoop {

    private static final Logger log = LoggerFactory.getLogger(ToolCallLoop.class);

    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final ObjectMapper objectMapper;

    public ToolCallLoop(ChatHttpHandler chatHttpHandler, @Lazy ToolExecutor toolExecutor, ObjectMapper objectMapper) {
        this.chatHttpHandler = chatHttpHandler;
        this.toolExecutor = toolExecutor;
        this.objectMapper = objectMapper;
    }

    // ==================== 工具方法 ====================

    /**
     * 从 context 中提取 session / chatSession 构建 AgentLog 回调。
     * 如果 context 中缺少必要信息则返回 null。
     */
    public Consumer<AgentLogEntry> createDefaultLogback(Map<String, Object> context) {
        Object sessionObj = context.get("session");
        Object chatSessionObj = context.get("chatSession");
        if (sessionObj instanceof WebSocketSession wsSession
                && chatSessionObj instanceof ChatSession cs) {
            String chatSessionId = cs.getId();
            return entry -> {
                try {
                    entry.setSessionId(chatSessionId);
                    String signal = ControlSign.SIGN_AGENT_LOG + objectMapper.writeValueAsString(entry);
                    wsSession.sendMessage(new TextMessage(signal));
                } catch (Exception e) {
                    log.warn("推送 AgentLog 失败: {}", e.getMessage());
                }
            };
        }
        return null;
    }

    // ==================== Hook 机制 ====================

    /**
     * 工具结果钩子 —— 在每一轮工具执行完毕后回调。
     * 调用方可以在此收集 / 过滤 / 转换工具返回结果，决定本轮数据是否保留。
     */
    @FunctionalInterface
    public interface ToolResultHook {
        /**
         * @param roundResults 本轮所有工具的 (名称, 参数, 结果)
         * @param aiText       本轮 AI 的纯文本输出（可能包含 true/false 等决策信号）
         * @return true 继续循环，false 终止循环
         */
        boolean onRound(List<RoundResult> roundResults, String aiText);
    }

    @Data
    @Accessors(chain = true)
    public static class AgentLoopHook {
        private Consumer<AgentLogEntry> logback;
        private ToolResultHook resultHook;

        public AgentLoopHook() {
        }

        public AgentLoopHook(Consumer<AgentLogEntry> logback, ToolResultHook resultHook) {
            this.logback = logback;
            this.resultHook = resultHook;
        }
    }

    /**
     * 单轮单次工具调用的结果快照。
     */
    public record RoundResult(String toolName, String arguments, String result) {
    }

    // ==================== 公开入口 ====================

    /**
     * 执行工具调用循环，阻塞直到 AI 返回最终文本。
     */
    public String execute(AISettings settings, ChatRequest request, Map<String, Object> context) {
        return execute(settings, request, context, null);
    }

    /**
     * 执行工具调用循环（带完整的 Hook 配置），阻塞直到 AI 返回最终文本。
     *
     * @param settings AI 模型配置
     * @param request  完整的 ChatRequest
     * @param context  工具执行上下文
     * @param hook     可选，包含日志回调和工具结果钩子，传 null 时自动从 context 构建默认日志回调
     * @return AI 最终文本回复
     */
    public String execute(AISettings settings, ChatRequest request, Map<String, Object> context, AgentLoopHook hook) {
        AtomicReference<String> result = new AtomicReference<>();
        try {
            if (hook == null) {
                hook = new AgentLoopHook(null, null);
            }
            if (hook.getLogback() == null) {
                hook.setLogback(createDefaultLogback(context));
            }
            loop(settings, context, result, request, hook, 1);
        } catch (Exception e) {
            log.error("ToolCallLoop 执行异常: {}", e.getMessage(), e);
            throw new RuntimeException("ToolCallLoop 执行失败: " + e.getMessage(), e);
        }
        return result.get();
    }

    // ==================== 递归循环 ====================

    private void loop(AISettings settings,
                      Map<String, Object> context,
                      AtomicReference<String> result,
                      ChatRequest request,
                      AgentLoopHook hook,
                      int iteration
    ) throws Exception {

        Consumer<AgentLogEntry> logCallback = hook != null ? hook.getLogback() : null;

        chatHttpHandler.translate(
                UUID.randomUUID().toString(),
                settings.getAdapterName(),
                request,
                settings.getStream(),
                null, // 无流式回调
                (translateResult, lastRes) -> {
                    List<AIAdapter.ToolCall> toolCalls = translateResult.toolCalls();
                    List<ChatMessage> messages = request.getMessages();

                    // 追加 assistant 消息（含 tool_calls 声明）
                    List<ChatToolRequest> chatToolReqs = ChatToolRequest.convert(toolCalls);

                    messages.add(new ChatMessage().assistant(translateResult.content(), translateResult.reasoning(), chatToolReqs));

                    // 无工具调用 → 返回最终文本
                    if (CollectionUtils.isEmpty(toolCalls)) {
                        result.set(translateResult.content());
                        if (logCallback != null) {
                            logCallback.accept(new AgentLogEntry()
                                    .setId(UUID.randomUUID().toString())
                                    .setPhase(AgentLogEntry.PHASE_DONE)
                                    .setAgentName("ToolCallLoop")
                                    .setTitle("第" + iteration + "轮：AI 完成，共 " + iteration + " 轮迭代")
                                    .setIteration(iteration));
                        }
                        return;
                    }

                    // 日志回调：本轮迭代信息
                    if (logCallback != null) {
                        List<String> toolNames = toolCalls.stream()
                                .map(tc -> tc.getFunction().getName())
                                .toList();
                        logCallback.accept(new AgentLogEntry()
                                .setId(UUID.randomUUID().toString())
                                .setPhase(AgentLogEntry.PHASE_ITERATION)
                                .setAgentName("ToolCallLoop")
                                .setTitle("第" + iteration + "轮：AI 决定调用 " + String.join(", ", toolNames))
                                .setContent(AgentLogEntry.truncate(translateResult.content()))
                                .setIteration(iteration));
                    }

                    // 并行执行工具
                    List<ToolExecutor.ToolRequest> reqs = ToolExecutor.ToolRequest.convert(toolCalls);

                    // 日志回调：每个工具调用（参数）
                    if (logCallback != null) {
                        for (AIAdapter.ToolCall tc : toolCalls) {
                            logCallback.accept(new AgentLogEntry()
                                    .setId(UUID.randomUUID().toString())
                                    .setPhase(AgentLogEntry.PHASE_TOOL_CALL)
                                    .setAgentName("ToolCallLoop")
                                    .setTitle("调用工具: " + tc.getFunction().getName())
                                    .setContent(AgentLogEntry.truncate(tc.getFunction().getArguments()))
                                    .setIteration(iteration));
                        }
                    }

                    // 不传 ToolExecuteNotifier provider：agent 循环内的工具只通过 ###AGENT_LOG### 进侧边栏，
                    // 不推送 tool_execution / tool_response 占位，避免 agent 工具执行状态泄漏到主对话页
                    List<ToolExecutor.ToolExecuteResponse> toolResults;
                    toolResults = toolExecutor.execute(reqs, context, null);

                    // --- Hook 回调：收集本轮工具结果 ---
                    if (hook != null && hook.getResultHook() != null) {
                        List<RoundResult> roundResults = new ArrayList<>();
                        for (int i = 0; i < reqs.size(); i++) {
                            ToolExecutor.ToolExecuteResponse trResp = toolResults.get(i);
                            roundResults.add(new RoundResult(
                                    reqs.get(i).getToolName(),
                                    reqs.get(i).getArguments(),
                                    trResp.getResult()));
                        }
                        if (!hook.getResultHook().onRound(roundResults, translateResult.content())) {
                            // hook 返回 false → 终止循环
                            result.set(translateResult.content());
                            return;
                        }
                    }

                    // 追加 tool 结果消息 + 日志回调：工具结果
                    for (int i = 0; i < toolCalls.size(); i++) {
                        String toolResult = toolResults.get(i).getResult();
                        messages.add(new ChatMessage()
                                .tool(toolCalls.get(i).getId(), toolResult)
                                .setName(toolCalls.get(i).getFunction().getName())
                        );
                        if (logCallback != null) {
                            boolean succeed = toolResults.get(i).isSucceed();
                            logCallback.accept(new AgentLogEntry()
                                    .setId(UUID.randomUUID().toString())
                                    .setPhase(AgentLogEntry.PHASE_TOOL_RESULT)
                                    .setAgentName("ToolCallLoop")
                                    .setTitle((succeed ? "✓ " : "✗ ") + toolCalls.get(i).getFunction().getName())
                                    .setContent(AgentLogEntry.truncate(toolResult))
                                    .setLevel(succeed ? "info" : "warn")
                                    .setIteration(iteration));
                        }
                    }

                    // 递归下一轮
                    try {
                        loop(settings, context, result, request, hook, iteration + 1);
                    } catch (Exception e) {
                        log.error("工具调用循环异常: {}", e.getMessage(), e);
                        throw new RuntimeException(e);
                    }
                }
        );
    }
}
