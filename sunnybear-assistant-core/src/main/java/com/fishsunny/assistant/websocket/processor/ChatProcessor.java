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
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.ChatToolRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.audio.AudioContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.image.ImageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.video.VideoContent;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.extension.ExtensionScriptService;
import com.fishsunny.assistant.engine.tool.instance.net.WebSearchTool;
import com.fishsunny.assistant.engine.tool.instance.net.WebReaderTool;
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
import com.fishsunny.assistant.variable.RoleVariable;
import com.fishsunny.assistant.websocket.ChatProvider;
import lombok.Getter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetAddress;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 本类主要用于处理核心对话逻辑，包括消息的传输与落盘
 */
@Component
public class ChatProcessor {

    private static final Logger log = LoggerFactory.getLogger(ChatProcessor.class);

    private final ChatMessageService chatMessageService;
    private final ObjectMapper objectMapper;
    private final AssistantSettings assistantSettings;
    private final AISettings aiSettings;
    private final AISettings chatProAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final KnowledgeService knowledgeService;
    private final MemoryService memoryService;
    private final ExtensionScriptService extensionScriptService;

    public ChatProcessor(ChatMessageService chatMessageService,
                            ObjectMapper objectMapper,
                            AssistantSettings assistantSettings,
                            ToolExecutor toolExecutor,
                            KnowledgeService knowledgeService,
                            MemoryService memoryService,
                            ExtensionScriptService extensionScriptService,
                            @Qualifier(AISettings.CHAT) AISettings aiSettings,
                            @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
                            ChatHttpHandler chatHttpHandler) {
        this.chatMessageService = chatMessageService;
        this.objectMapper = objectMapper;
        this.assistantSettings = assistantSettings;
        this.toolExecutor = toolExecutor;
        this.knowledgeService = knowledgeService;
        this.memoryService = memoryService;
        this.extensionScriptService = extensionScriptService;
        this.aiSettings = aiSettings;
        this.chatProAISettings = chatProAISettings;
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
        AISettings effectiveAISettings = (chatSession.getEnablePro() != null && chatSession.getEnablePro())
                ? chatProAISettings : aiSettings;
        if (effectiveAISettings != aiSettings) {
            log.debug("会话 {} 使用 chat_pro 模型: {}", chatSession.getId(), chatProAISettings.getModel());
        }

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
        if (!RoleVariable.ROLE_USER.equals(userMessage.getRole()) && !RoleVariable.ROLE_TOOL.equals(userMessage.getRole())) {
            throw new UserException("用户消息角色无效: " + userMessage.getRole());
        }

        List<ChatMessage> collector = new ArrayList<>();

        // 斜杠指令拦截：以 / 开头的消息走本地处理，不调用 AI
        String userText = userMessage.resolveText();
        Pattern pattern = Pattern.compile("^/[a-zA-Z]+");
        if (userText != null && pattern.matcher(userText).find()) {
            return handleSlashCommand(userText, chatSession, session, collector, originMessages);
        }

        toolCallCycle(collector, 0, request, chatSession, session, chatProvider);
        return collector;
    }

    private String defaultSystemPrompt(ChatProvider.SystemProviderContext context, String effectiveModelName,
                                       WebSocketSession session) throws Exception {
        ChatSession chatSession = context.chatSession();
        List<ChatMessage> originMessages = context.originMessages();

        // 替换变量（系统提示词始终使用 chat 的 prompt，模型名使用实际生效的模型）
        String systemPrompt = aiSettings.getPrompt()
                .replace(PromptReplaceVariable.CURRENT_TIME, LocalDate.now().toString())
                .replace(PromptReplaceVariable.MODEL_NAME, effectiveModelName)
                .replace(PromptReplaceVariable.IP_ADDRESS, InetAddress.getLocalHost().toString());


        // 知识库匹配：用用户最新消息做 embedding 匹配知识条目
        try {
            ChatMessage lastUserMsg = ObjectUtils.getLast(originMessages);
            if (lastUserMsg != null) {
                String queryText = lastUserMsg.resolveText();
                KnowledgeService.KnowledgeSection knowledgeResult = knowledgeService.buildKnowledgeSection(chatSession.getId(), queryText);
                if (StringUtils.hasText(knowledgeResult.text())) {
                    systemPrompt += knowledgeResult.text();
                    log.debug("知识库注入成功");
                    // 控制信号通知前端：仅当本轮去重后注入了新知识条目时才推送命中信号
                    if (knowledgeResult.hasNew()) {
                        try {
                            session.sendMessage(new TextMessage(ControlSign.SIGN_KNOWLEDGE_HIT + chatSession.getId()));
                        } catch (Exception e) {
                            log.warn("发送知识库命中信号失败: {}", e.getMessage());
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("知识库匹配失败: {}", e.getMessage());
        }


        // 注入核心记忆
        try {
            String memorySection = memoryService.buildMemorySection();
            if (StringUtils.hasText(memorySection)) {
                systemPrompt += memorySection;
                log.debug("核心记忆注入成功");
            }
        } catch (Exception e) {
            log.warn("核心记忆注入失败: {}", e.getMessage());
        }

        // 注入扩展脚本描述
        try {
            String scriptSection = extensionScriptService.buildScriptSection();
            if (StringUtils.hasText(scriptSection)) {
                systemPrompt += scriptSection;
                log.debug("扩展脚本描述注入成功");
            }
        } catch (Exception e) {
            log.warn("扩展脚本描述注入失败: {}", e.getMessage());
        }

        // 注入已上传文件信息：当用户消息中包含文件附件时，告知 AI 会话中有哪些文件
        ChatMessage lastUserMsg = ObjectUtils.getLast(originMessages);
        if (lastUserMsg != null && lastUserMsg.getContents() != null) {
            List<MessageContent> fileContents = new ArrayList<>();
            for (MessageContent c : lastUserMsg.getContents()) {
                if (!(c instanceof TextContent)) {
                    fileContents.add(c);
                }
            }
            if (!fileContents.isEmpty()) {
                StringBuilder fileSection = new StringBuilder("\n[user_uploaded_files]\n");
                fileSection.append("用户在本轮对话中上传了以下文件，你可以通过 session_file_tool 查看完整列表：\n");
                for (int i = 0; i < fileContents.size(); i++) {
                    MessageContent c = fileContents.get(i);
                    String typeName = c.getClass().getSimpleName().replace("Content", "");
                    String url = null;
                    if (c instanceof ImageContent ic) url = ic.getUrl();
                    else if (c instanceof VideoContent vc) url = vc.getUrl();
                    else if (c instanceof AudioContent ac) url = ac.getUrl();
                    else if (c instanceof FileContent fc) url = fc.getUrl();

                    // 从路径中提取文件名
                    String fileName = "未知文件";
                    if (url != null) {
                        String name = url.replace('\\', '/');
                        int lastSlash = name.lastIndexOf('/');
                        fileName = lastSlash >= 0 ? name.substring(lastSlash + 1) : name;
                        fileName = fileName.replaceFirst("^\\d+_", ""); // 去掉 index_ 前缀
                    }
                    fileSection.append(i + 1).append(". [").append(typeName).append("] ").append(fileName).append("\n");
                }
                systemPrompt += fileSection.toString();
                log.debug("文件信息注入成功: {} 个文件", fileContents.size());
            }
        }
        return systemPrompt;
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
                               int times,
                               ChatRequest request,
                               ChatSession chatSession,
                               WebSocketSession session,
                               ChatProvider chatProvider) throws Exception {
        // 根据 session 的 enable_pro 标记选择模型
        AISettings effectiveAISettings = (chatSession.getEnablePro() != null && chatSession.getEnablePro())
                ? chatProAISettings : aiSettings;

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
                    message.setSessionId(chatSession.getId());
                    message.setName(assistantSettings.getAssistantName());
                    ChatMessage last = ObjectUtils.getLast(request.getMessages());
                    String parentId = last == null ? null : last.getId();
                    message.setParentId(parentId);
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
                ChatResponse response = new ChatResponse().afterAIResponse(assistantMessage, chatSession.getId());
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
                        toolMessages.add(new ChatMessage().tool(
                                chatSession.getId(), last.getId(), toolcall.getId(),
                                toolcall.getFunction().getName(), objectMapper.writeValueAsString(toolResult)));
                    } catch (Exception e) {
                        log.error("构建工具消息失败: {}", e.getMessage());
                    }
                }
                // 保存工具消息
                try {
                    List<ChatMessage> toolResponseMessages = appendToolMessage(toolMessages);
                    request.getMessages().addAll(toolResponseMessages);
                    ChatResponse response = new ChatResponse().afterToolCall(toolResponseMessages, chatSession.getId());
                    session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
                    collector.addAll(toolResponseMessages);
                } catch (Exception e) {
                    log.error("保存工具消息失败: {}", e.getMessage());
                    throw new RuntimeException(e);
                }
                // 递归调用
                try {
                    int newTimes = times + 1;
                    toolCallCycle(collector, newTimes, request, chatSession, session, chatProvider);
                } catch (Exception e) {
                    log.error("工具调用失败: {}", e.getMessage());
                    throw new RuntimeException(e);
                }
            }
        };

        chatHttpHandler.translate(chatSession.getId(), effectiveAISettings.getAdapterName(), request, request.getSettings().getStream(), translate, complete);
    }

    private ChatMessage appendAssistantMessage(String sessionId, String parentId, String reasoning, String content, List<ChatToolRequest> toolCalls) throws Exception {

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setName(assistantSettings.getAssistantName());
        chatMessage.setRole(RoleVariable.ROLE_ASSISTANT);
        chatMessage.setSessionId(sessionId);
        chatMessage.setParentId(parentId);
        chatMessage.setToolCalls(toolCalls);
        chatMessage.setReasoningContent(reasoning);
        List<MessageContent> contents = new ArrayList<>();
        contents.add(new TextContent(content));
        chatMessage.setContents(contents);

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

    // ==================== 斜杠指令处理 ====================

    /**
     * 斜杠指令分发 —— 以 / 开头的消息不走 AI，本地处理、落盘、返回。
     */
    private List<ChatMessage> handleSlashCommand(String content, ChatSession chatSession,
                                                  WebSocketSession session, List<ChatMessage> collector,
                                                  List<ChatMessage> originMessages) throws Exception {
        String[] parts = content.split("\\s+", 2);
        String cmd = parts[0].toLowerCase();
        String args = parts.length > 1 ? parts[1] : "";

        // /look 走流式传输：MissionAI 逐字生成摘要
        if ("/look".equals(cmd)) {
            String[] lookParts = args.split("\\s+", 2);
            String sessionId = lookParts.length > 0 ? lookParts[0] : "";
            String prompt = lookParts.length > 1 ? lookParts[1] : "";
            return lookSessionStreaming(sessionId, prompt, chatSession, session, collector, originMessages);
        }

        // 其他指令：非流式，直接生成完整结果
        String result = switch (cmd) {
            default -> "**未知指令**：`" + cmd + "`\n\n当前仅支持 `/look <sessionId>` 查看会话记录。";
        };

        ChatMessage lastOrigin = ObjectUtils.getLast(originMessages);
        String parentId = lastOrigin != null ? lastOrigin.getId() : null;
        ChatMessage assistantMessage = appendAssistantMessage(chatSession.getId(), parentId, null, result, List.of());

        ChatResponse response = new ChatResponse()
                .setStatus(ChatResponse.STATUS_INIT_ASSISTANT)
                .setMessages(List.of(assistantMessage))
                .setSessionId(chatSession.getId());
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));

        collector.add(assistantMessage);
        return collector;
    }

    /** /look <sessionId> [关注点] —— 拉取会话历史，流式输出 MissionAI 摘要 */
    private List<ChatMessage> lookSessionStreaming(String sessionId, String prompt,
                                                    ChatSession chatSession, WebSocketSession session,
                                                    List<ChatMessage> collector,
                                                    List<ChatMessage> originMessages) throws Exception {
        // 参数校验
        if (sessionId == null || sessionId.isBlank()) {
            String usage = "**用法**：`/look <sessionId> [关注点]`\n\n请提供要查看的会话 ID。在输入框中输入 `/look` 可从侧边栏选择会话。";
            ChatMessage errorMsg = appendAssistantMessage(chatSession.getId(), getParentId(originMessages), null, usage, List.of());
            sendAssistantResponse(session, chatSession.getId(), errorMsg);
            collector.add(errorMsg);
            return collector;
        }

        // 拉取历史
        List<ChatMessage> history;
        try {
            history = chatMessageService.getConversationHistory(sessionId.trim());
        } catch (Exception e) {
            String err = "**查询失败**：会话 `" + sessionId + "` 不存在或无法访问。";
            ChatMessage errorMsg = appendAssistantMessage(chatSession.getId(), getParentId(originMessages), null, err, List.of());
            sendAssistantResponse(session, chatSession.getId(), errorMsg);
            collector.add(errorMsg);
            return collector;
        }
        if (history == null || history.isEmpty()) {
            String empty = "**会话 `" + sessionId + "` 暂无对话记录。**";
            ChatMessage emptyMsg = appendAssistantMessage(chatSession.getId(), getParentId(originMessages), null, empty, List.of());
            sendAssistantResponse(session, chatSession.getId(), emptyMsg);
            collector.add(emptyMsg);
            return collector;
        }

        // 拼接对话历史
        StringBuilder historyText = new StringBuilder();
        for (ChatMessage msg : history) {
            String role = msg.getRole();
            String text = msg.resolveText();
            if (text == null || text.isBlank()) continue;
            String label = switch (role) {
                case "user" -> "用户";
                case "assistant" -> "助手";
                case "tool" -> "工具";
                default -> role;
            };
            if (text.length() > 500) text = text.substring(0, 500) + "…";
            historyText.append("[").append(label).append("] ")
                       .append(msg.getName() != null ? msg.getName() + "：" : "")
                       .append(text.replace("\n", " ")).append("\n\n");
        }

        // 提取 chat AI 的系统提示词，与待总结文本一同放入 user prompt
        String focusLine = prompt.isBlank() ? "" : "请重点关注以下方面：" + prompt + "\n";
        String chatSystemPrompt = aiSettings.getPrompt() != null ? aiSettings.getPrompt() : "";

        String userPrompt = """
                ## 当前角色设定
                %s

                ## 会话 ID：%s
                %s
                ## 对话历史
                %s

                请以当前角色设定的视角，对上述对话历史生成一段简洁有条理的摘要（3-5 段）。
                如果指定了关注重点，请围绕该重点展开；否则概括全文的要点和关键信息。
                使用 Markdown 格式输出，包含标题和分点。"""
                .formatted(
                        chatSystemPrompt.isBlank() ? "（无特殊角色设定）" : chatSystemPrompt,
                        sessionId.trim(),
                        focusLine,
                        historyText.toString());

        ChatRequest request = new ChatRequest()
                .loadSettings(aiSettings)
                .setMessages(List.of(new ChatMessage().user(userPrompt)));

        String header = "## 📜 会话摘要：`" + sessionId.trim() + "`\n\n";
        AtomicBoolean isFirst = new AtomicBoolean(true);
        chatHttpHandler.translate(UUID.randomUUID().toString(), aiSettings.getAdapterName(), request, aiSettings.getStream(),
                tr -> {
                    ChatResponse masterResp = (ChatResponse) tr;
                    if (isFirst.get() && StringUtils.hasText(masterResp.getText())) {
                        masterResp.appendTextAtStart(header);
                        isFirst.set(false);
                    }
                    masterResp.setSessionId(chatSession.getId());
                    try {
                        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(masterResp)));
                    } catch (Exception e) {
                        log.warn("流式推送 /look chunk 失败: {}", e.getMessage());
                    }
                },
                (trResult, lastRes) -> {
                    try {
                        ChatMessage saved = appendAssistantMessage(chatSession.getId(), getParentId(originMessages),
                                trResult.reasoning(), header + trResult.content(), List.of());
                        sendAssistantResponse(session, chatSession.getId(), saved);
                        collector.add(saved);
                    } catch (Exception e) {
                        log.error("/look 落盘失败: {}", e.getMessage());
                    }
                }
        );

        return collector;
    }

    private String getParentId(List<ChatMessage> originMessages) {
        ChatMessage last = ObjectUtils.getLast(originMessages);
        return last != null ? last.getId() : null;
    }

    private void sendAssistantResponse(WebSocketSession session, String sessionId, ChatMessage msg) throws Exception {
        ChatResponse resp = new ChatResponse()
                .setStatus(ChatResponse.STATUS_INIT_ASSISTANT)
                .setMessages(List.of(msg))
                .setSessionId(sessionId);
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(resp)));
    }
}
