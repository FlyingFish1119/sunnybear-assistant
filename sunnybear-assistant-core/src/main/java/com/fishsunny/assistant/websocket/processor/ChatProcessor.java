package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 06:56
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.adapter.AIAdapter;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.net.WebReaderTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebSearchTool;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.ToolContextBuilder;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.variable.PromptReplaceVariable;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.processor.slash.framwork.SlashCommandExecutor;
import com.fishsunny.assistant.websocket.processor.slash.framwork.SlashCommandHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.*;

/**
 * 本类主要用于处理核心对话逻辑，包括消息的传输与落盘
 */
@Component
@Slf4j
public class ChatProcessor {

    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final AssistantSettings assistantSettings;
    private final AISettings aiSettings;
    private final AISettings chatProAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final KnowledgeService knowledgeService;
    private final MemoryService memoryService;
    private final SlashCommandExecutor slashCommandExecutor;

    public ChatProcessor(ChatMessageService chatMessageService,
                            ObjectMapper objectMapper,
                            AssistantSettings assistantSettings,
                            ToolExecutor toolExecutor,
                            KnowledgeService knowledgeService,
                            MemoryService memoryService,
                            @Qualifier(AISettings.CHAT) AISettings aiSettings,
                            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
                            SlashCommandExecutor slashCommandExecutor,
                            ChatHttpHandler chatHttpHandler) {
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.assistantSettings = assistantSettings;
        this.toolExecutor = toolExecutor;
        this.knowledgeService = knowledgeService;
        this.memoryService = memoryService;
        this.aiSettings = aiSettings;
        this.chatProAISettings = chatProAISettings;
        this.slashCommandExecutor = slashCommandExecutor;
        this.chatHttpHandler = chatHttpHandler;
    }

    /**
     * 核心对话处理逻辑
     */
    public List<ChatMessage> chatToAi(List<ChatMessage> originMessages,
                                      ChatSession chatSession,
                                      WebSocketSession session,
                                      ChatProvider chatProvider
                                      ) throws Exception {
        if (chatProvider == null) {
            throw new UserException("无效的 ChatProvider");
        }

        // 根据 session 的 enable_pro 标记选择使用 chat 还是 chat_pro 模型
        AISettings effectiveAISettings = Boolean.TRUE.equals(chatSession.getEnablePro()) ? chatProAISettings : aiSettings;

        // 获取系统提示
        ChatProvider.SystemProviderContext context = new ChatProvider.SystemProviderContext(chatSession, originMessages);
        String systemPrompt;
        if (chatProvider.getSystemProvider() != null) {
            systemPrompt = chatProvider.getSystemProvider().apply(context);
        } else {
            systemPrompt = defaultSystemPrompt(context, effectiveAISettings.getModel(), session);
        }

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage().system(systemPrompt));
        messages.addAll(ChatMessage.fillAllFile(originMessages));

        ChatRequest request = new ChatRequest()
                .setMessages(messages)
                .loadSettings(effectiveAISettings);

        ChatMessage userMessage = ObjectUtils.getLast(request.getMessages());
        if (userMessage == null) {
            throw new UserException("用户消息为空");
        }
        if (!ChatMessage.ROLE_USER.equals(userMessage.getRole()) && !ChatMessage.ROLE_TOOL.equals(userMessage.getRole())) {
            throw new UserException("用户消息角色无效: " + userMessage.getRole());
        }

        SlashCommandHandler.SlashCommandContext slashCommandContext = new SlashCommandHandler.SlashCommandContext(
                userMessage.resolveText(), session, chatSession, messages, new ArrayList<>()
        );
        if (slashCommandExecutor.runSlashFactory(slashCommandContext)) {
            return slashCommandContext.resultMessage();
        }

        List<ChatMessage> collector = new ArrayList<>();
        toolCallCycle(collector, effectiveAISettings, request, chatSession, session, chatProvider);
        return collector;
    }

    private String defaultSystemPrompt(ChatProvider.SystemProviderContext context, String effectiveModelName,
                                       WebSocketSession session) throws Exception {
        ChatSession chatSession = context.chatSession();
        List<ChatMessage> originMessages = context.originMessages();

        // 替换变量（系统提示词始终使用 chat 的 prompt，模型名使用实际生效的模型）
        StringBuilder systemPrompt = new StringBuilder(aiSettings.getPrompt()
                .replace(PromptReplaceVariable.CURRENT_TIME, LocalDate.now().toString())
                .replace(PromptReplaceVariable.MODEL_NAME, effectiveModelName)
                .replace(PromptReplaceVariable.IP_ADDRESS, InetAddress.getLocalHost().toString()));

        injectKnowledgePrompt(session, originMessages, chatSession, systemPrompt);
        injectMemoryPrompt(systemPrompt);

        return systemPrompt.toString();
    }

    private void injectKnowledgePrompt(WebSocketSession session, List<ChatMessage> originMessages, ChatSession chatSession, StringBuilder systemPrompt) {
        // 知识库匹配：用用户最新消息做 embedding 匹配知识条目
        try {
            ChatMessage lastUserMsg = ObjectUtils.getLast(originMessages);
            if (lastUserMsg == null) {
                return;
            }
            String queryText = lastUserMsg.resolveText();
            KnowledgeService.KnowledgeSection knowledgeResult = knowledgeService.buildKnowledgeSection(chatSession.getId(), queryText);
            if (!StringUtils.hasText(knowledgeResult.text())) {
                return;
            }
            systemPrompt.append(knowledgeResult.text());
            if (!knowledgeResult.hasNew()) {
                return;
            }
            // 控制信号通知前端：仅当本轮去重后注入了新知识条目时才推送命中信号
            try {
                session.sendMessage(new TextMessage(ControlSign.SIGN_KNOWLEDGE_HIT + chatSession.getId()));
            } catch (Exception e) {
                log.warn("发送知识库命中信号失败: {}", e.getMessage());
            }
        } catch (Exception e) {
            log.warn("知识库匹配失败: {}", e.getMessage());
        }
    }

    private void injectMemoryPrompt(StringBuilder systemPrompt) {
        try {
            String memorySection = memoryService.buildMemorySection();
            if (StringUtils.hasText(memorySection)) {
                systemPrompt.append(memorySection);
            }
        } catch (Exception e) {
            log.warn("核心记忆注入失败: {}", e.getMessage());
        }
    }

    /** 主 Agent 不直接调用的工具（由子 Agent 代理：net_explore_tool、comfyui_tool） */
    @Getter
    private static final Set<String> EXCLUDE_TOOLS = new HashSet<>();
    static {
        EXCLUDE_TOOLS.add(WebSearchTool.NAME);
        EXCLUDE_TOOLS.add(WebReaderTool.NAME);
    }

    /**
     * 工具调用循环
     */
    private void toolCallCycle(List<ChatMessage> collector,
                               AISettings effectiveAISettings,
                               ChatRequest request,
                               ChatSession chatSession,
                               WebSocketSession session,
                               ChatProvider chatProvider
    ) throws Exception {

        // 注入工具（排除原始 net 工具，由 net_agent_tool 子 Agent 统一代理）
        List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegisterExcluding(
                toolExecutor, EXCLUDE_TOOLS);
        if (chatProvider.getToolProvider() != null) {
            ChatProvider.ToolProviderContext toolCtx = new ChatProvider.ToolProviderContext(chatSession, toolRegisters);
            toolRegisters = chatProvider.getToolProvider().apply(toolCtx);
        }
        request.setTools(toolRegisters);

        // 定义响应处理
        ChatHttpHandler.InTranslateCallback translate = response -> {
            ChatResponse chatResponse = (ChatResponse) response;
            try {
                chatResponse.setSessionId(chatSession.getId());
                for (ChatMessage message : chatResponse.getMessages()) {
                    ChatMessage last = ObjectUtils.getLast(request.getMessages());
                    String parentId = last == null ? null : last.getId();
                    message.makeInsertable(chatSession.getId(), parentId, assistantSettings.getAssistantName());
                }
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(chatResponse)));
            } catch (Exception e) {
                log.error("发送消息失败: {}", e.getMessage());
            }
        };

        // 定义回调
        ChatHttpHandler.CompleteCallback complete = (result, lastResp) -> {
            var toolCalls = result.toolCalls();
            boolean haveToolCall = !CollectionUtils.isEmpty(toolCalls);

            // 落盘w
            try {
                ChatMessage last = ObjectUtils.getLast(request.getMessages());
                String parentId = last == null ? null : last.getId();
                List<ChatToolRequest> toolCallRequests = new ArrayList<>();
                // 如果 AI 调用了工具，将工具调用的参数注入
                if (haveToolCall) {
                    List<ChatToolRequest> convert = ChatToolRequest.convert(toolCalls);
                    toolCallRequests.addAll(convert);
                }
                ChatMessage assistantMessage = appendAssistantMessage(chatSession.getId(), parentId, result.reasoning(), result.content(), toolCallRequests);
                // A\专用的
                String reasoningSignature = result.reasoningSignature();
                if (reasoningSignature != null && !reasoningSignature.isEmpty()) {
                    assistantMessage.setReasoningSignature(reasoningSignature);
                }
                // 添加助手消息
                ChatResponse response = new ChatResponse().afterAIResponse(assistantMessage);
                session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                request.getMessages().add(assistantMessage);
                collector.add(assistantMessage);
            } catch (Exception e) {
                log.error("保存助手消息失败: {}", e.getMessage());
                throw new RuntimeException("保存助手消息失败: " + e.getMessage());
            }

            // 执行工具
            if (haveToolCall) {
                ChatMessage last = ObjectUtils.getLast(request.getMessages());
                if (last == null) {
                    throw new RuntimeException("Error message data: missed tool call message");
                }
                // 构建批量请求
                List<ToolExecutor.ToolRequest> toolRequests = ToolExecutor.ToolRequest.convert(toolCalls);

                // 并行执行
                // 构建上下文
                Map<String, Object> context = ToolContextBuilder.minimumBuild(session, chatSession);
                if (chatProvider.getContextProvider() != null) {
                    context = chatProvider.getContextProvider().apply(context);
                }
                List<ToolExecutor.ToolExecuteResponse> toolResults = toolExecutor.execute(toolRequests, context);
                // 构建工具消息
                List<ChatMessage> toolMessages = new ArrayList<>();
                for (int i = 0; i < toolCalls.size(); i++) {
                    AIAdapter.ToolCall toolcall = toolCalls.get(i);
                    ToolExecutor.ToolExecuteResponse toolResult = toolResults.get(i);
                    try {
                        toolMessages.add(new ChatMessage()
                                .tool(toolcall.getId(), objectMapper.writeValueAsString(toolResult))
                                .makeInsertable(chatSession.getId(), last.getId(), toolcall.getFunction().getName())
                        );
                    } catch (Exception e) {
                        log.error("构建工具消息失败: {}", e.getMessage());
                    }
                }
                // 保存工具消息
                try {
                    List<ChatMessage> toolResponseMessages = appendToolMessage(toolMessages);
                    request.getMessages().addAll(toolResponseMessages);
                    ChatResponse response = new ChatResponse().afterToolCall(toolResponseMessages);
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                    collector.addAll(toolResponseMessages);
                } catch (Exception e) {
                    log.error("保存工具消息失败: {}", e.getMessage());
                    throw new RuntimeException(e);
                }
                // 递归调用
                try {
                    toolCallCycle(collector, effectiveAISettings, request, chatSession, session, chatProvider);
                } catch (Exception e) {
                    log.error("工具调用失败: {}", e.getMessage());
                    throw new RuntimeException(e);
                }
            }
        };

        chatHttpHandler.translate(chatSession.getId(), effectiveAISettings.getAdapterName(), request, request.getSettings().getStream(), translate, complete);
    }

    private ChatMessage appendAssistantMessage(String sessionId, String parentId, String reasoning, String content, List<ChatToolRequest> toolCalls) throws Exception {

        ChatMessage chatMessage = new ChatMessage()
                .assistant(content, reasoning, toolCalls)
                .makeInsertable(sessionId, parentId, assistantSettings.getAssistantName());

        try {
            return chatMessageService.save(chatMessage);
        } catch (UserException e) {
            throw new UserException("保存助手消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存助手消息失败，系统发生了未知错误: " + e.getMessage());
        }
    }

    private List<ChatMessage> appendToolMessage(List<ChatMessage> messages) throws Exception {
        List<ChatMessage> resultMessages = new ArrayList<>();
        try {
            for (ChatMessage message : messages) {
                chatMessageService.save(message);
                resultMessages.add(message);
            }
            return resultMessages;
        } catch (UserException e) {
            throw new UserException("保存工具结果消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存工具结果消息失败，系统发生了未知错误: " + e.getMessage());
        }
    }

    private void sendAssistantResponse(WebSocketSession session, String sessionId, ChatMessage msg) throws Exception {
        ChatResponse resp = new ChatResponse()
                .setStatus(ChatResponse.STATUS_INIT_ASSISTANT)
                .setMessages(List.of(msg))
                .setSessionId(sessionId);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
    }
}
