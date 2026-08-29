package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage WebSocket 消息业务处理器 —— 串联 消息持久化 + AI 调用 + 流式响应
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.dto.FileData;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.mvc.service.CronJobService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import com.fishsunny.assistant.websocket.SynchronizedWebSocketSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 本类主要和元数据处理逻辑有关
 */
@Component
public class ServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(ServiceProcessor.class);

    /** 会话标题生成器系统提示词（由 cub AI 承担该任务，prompt 固化在此） */
    private static final String TITLE_PROMPT = """
            你是一个专业的对话标题生成器。你的任务是根据给定的AI系统提示词和用户的第一条消息，为整段对话生成一个简洁、自然的标题。

            要求：
            - 标题长度尽量控制在15个汉字（或10个英文单词）以内。
            - 准确概括对话的核心主题或用户的主要意图，而不是简单重复原话。
            - 不要使用[对话]、[聊天]、[关于]、[求助]这类泛指词，要给出具体信息。
            - 标题语言要与用户输入的语言保持一致。
            - 只输出一个 JSON 对象，格式为 {"title": "标题"}，不要包含 markdown 代码块标记或任何其他文字。
            """;

    @Value("${assistant.file.base-path:}")
    private String basePath;

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final CronJobService cronJobService;
    private final ObjectMapper objectMapper;
    private final UserSettings userSettings;
    private final AssistantSettings assistantSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings cubAISettings;
    private final SessionMessageBus sessionMessageBus;
    public ServiceProcessor(ChatMessageService chatMessageService,
                            ChatSessionService chatSessionService,
                            CronJobService cronJobService,
                            ObjectMapper objectMapper,
                            UserSettings userSettings,
                            AssistantSettings assistantSettings,
                            ChatHttpHandler chatHttpHandler,
                            @Qualifier(AISettings.CUB) AISettings cubAISettings,
                            SessionMessageBus sessionMessageBus
                            ) {
        this.chatMessageService = chatMessageService;
        this.chatSessionService = chatSessionService;
        this.cronJobService = cronJobService;
        this.objectMapper = objectMapper;
        this.userSettings = userSettings;
        this.assistantSettings = assistantSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.cubAISettings = cubAISettings;
        this.sessionMessageBus = sessionMessageBus;
    }

    public ChatSessionModeParseResult handleChatSession(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, boolean isEnablePro) throws Exception {
        ChatSession chatSession;
        boolean isNewChat;
        List<ChatMessage> messages = new ArrayList<>();

        switch (request.getMode()) {
            case ChatMessageRequest.MODE_CREATE:
                isNewChat = true;
                // cron 触发：通过 cronId 查库获取标题，session type = 'cron'
                if (request.getCronId() != null) {
                    chatSession = createCronChatSession(request.getCronId());
                } else {
                    boolean enablePro = isEnablePro && judgeProModel(request.getContent());
                    chatSession = createChatSession(enablePro);
                }
                sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
                request.setSessionId(chatSession.getId());
                // 先写文件，再创建带文件引用的用户消息
                List<String> createFileUrls = writeSessionFile(request.getFiles(), chatSession);
                messages.add(appendUserMessage(
                        chatSession.getId(), null, request.getContent(), createFileUrls));
                break;
            case ChatMessageRequest.MODE_APPEND:
                isNewChat = false;
                chatSession = findChatSession(request.getSessionId());
                sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
                messages = findHistoryMessages(request.getSessionId());
                ChatMessage last = ObjectUtils.getLast(messages);
                String parentId = last == null ? null : last.getId();
                List<String> appendFileUrls = writeSessionFile(request.getFiles(), chatSession);
                messages.add(appendUserMessage(
                        request.getSessionId(), parentId, request.getContent(), appendFileUrls));
                break;
            case ChatMessageRequest.MODE_REPLACE:
                isNewChat = false;
                chatSession = findChatSession(request.getSessionId());
                sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
                messages = handleReplace(request, safeSession);
                sessionMessageBus.publish(chatSession.getId(), ControlSign.SIGN_REPLACE + request.getReplaceMessageId());
                break;
            case ChatMessageRequest.MODE_EDIT:
                isNewChat = false;
                chatSession = findChatSession(request.getSessionId());
                sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
                messages = handleEdit(request);
                break;
            default:
                throw new UserException("无效的请求模式");
        }

        return new ChatSessionModeParseResult(chatSession, isNewChat, messages);
    }

    public record ChatSessionModeParseResult(ChatSession chatSession, boolean isNewChat, List<ChatMessage> messages) {
    }


    /**
     * 创建会话
     * @param enablePro 是否启用高级模型
     */
    private ChatSession createChatSession(boolean enablePro) throws Exception {
        ChatSession chatSession = new ChatSession("新会话");
        chatSession.setEnablePro(enablePro);
        try {
            return chatSessionService.save(chatSession);
        } catch (Exception e) {
            log.error("Error create chat session: {}", e.getMessage());
            throw new UserException("创建会话失败: " + e.getMessage());
        }
    }

    /**
     * 为 cron 定时任务创建会话，标题为 "cron任务标题_时间戳"
     * @param cronId cron 任务 ID
     */
    private ChatSession createCronChatSession(Integer cronId) throws Exception {
        CronJob cronJob = cronJobService.findById(cronId);
        if (cronJob == null) {
            throw new UserException("cron 任务不存在: " + cronId);
        }
        String name = cronJob.getTitle() + "_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        ChatSession chatSession = new ChatSession(name);
        chatSession.setType("cron");
        chatSession.setEnablePro(cronJob.getEnablePro() != null && cronJob.getEnablePro());
        try {
            return chatSessionService.save(chatSession);
        } catch (Exception e) {
            log.error("Error create cron chat session: {}", e.getMessage());
            throw new UserException("创建 cron 会话失败: " + e.getMessage());
        }
    }

    /**
     * 使用 mission AI 判断用户问题是否需要使用复杂模型（chat_pro）。
     * @return true = 需要高级模型，false = 普通模型即可
     */
    private boolean judgeProModel(String userQuestion) {
        if (userSettings.getEnableAutoSwitchModel() == null || !userSettings.getEnableAutoSwitchModel()) {
            return false;
        }

        String judgmentPrompt = """
                你是一个问题复杂度判断器。分析用户的问题，判断它是否需要使用更强大的模型来回答。

                需要复杂模型的典型特征（满足任意一条即可）：
                1. 需要多步骤推理或深度分析
                2. 涉及代码编写、调试、架构设计
                3. 需要处理复杂数学、逻辑或科学问题
                4. 要求生成长篇、结构化内容（如报告、文档、方案）
                5. 涉及多领域交叉知识
                6. 问题表述详细、有多个子问题或约束条件

                不需要复杂模型的典型特征：
                1. 简单的事实性问题或定义查询
                2. 日常闲聊、问候
                3. 简单的翻译或文本改写
                4. 单一明确答案的查询

                请只回复一个单词：true（需要复杂模型）或 false（不需要复杂模型）。

                用户问题：
                %s
                """.formatted(userQuestion);

        ChatRequest request = new ChatRequest()
                .loadSettings(cubAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(judgmentPrompt),
                        new ChatMessage().user(userQuestion)
                ));

        try {
            AtomicBoolean result = new AtomicBoolean(false);
            chatHttpHandler.translate(UUID.randomUUID().toString(), cubAISettings.getAdapterName(),
                    request, cubAISettings.getStream(), null,
                    (trResult, lastRes) -> {
                        String content = trResult.content();
                        if (content != null) {
                            result.set(content.trim().toLowerCase().contains("true"));
                        }
                    }
            );
            return result.get();
        } catch (Exception e) {
            log.warn("模型复杂度判断失败，默认使用标准模型: {}", e.getMessage());
            return false;
        }
    }

    public void generateTitle(ChatSession chatSession, String userPrompt, String responsePrompt) throws Exception {
        String prompt = """
                请参考下面的内容生成一个会话标题：
                目标 AI 系统提示词：${systemPrompt}。
                用户给这个 AI 发送的消息：${userPrompt}
                AI 回复的信息：${assistantPrompt}
                """;
        prompt = prompt.replace("${systemPrompt}", assistantSettings.getAssistantName());
        prompt = prompt.replace("${userPrompt}", userPrompt);
        prompt = prompt.replace("${assistantPrompt}", responsePrompt);

        ChatRequest request = new ChatRequest()
                .loadSettings(new AISettings().copy(cubAISettings).json())
                .setMessages(List.of(
                        new ChatMessage().system(TITLE_PROMPT),
                        new ChatMessage().user(prompt)
                ));
        try {
            ChatHttpHandler.CompleteCallback onComplete = (result, lastRes) -> {
                chatSession.setName(parseTitle(result.content()));
            };
            chatHttpHandler.translate(UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request, cubAISettings.getStream(),
                    null, onComplete);
            chatSessionService.update(chatSession);
            sessionMessageBus.publish(chatSession.getId(), ControlSign.UPDATE_SESSION + objectMapper.writeValueAsString(chatSession));
        } catch (Exception e) {
            log.error("Error title generate: {}", e.getMessage());
        }
    }

    /**
     * 从 cub 返回的 JSON 中解析标题字段，期望格式：{"title": "标题"}。
     * 容错处理 ```json 代码块包裹，解析失败时回退为原始文本。
     */
    private String parseTitle(String raw) {
        if (!StringUtils.hasText(raw)) {
            return raw;
        }
        String json = raw.trim()
                .replaceAll("^```(json)?\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            String title = parsed.get("title");
            if (StringUtils.hasText(title)) {
                return title.trim();
            }
        } catch (Exception e) {
            log.warn("解析标题 JSON 失败，回退为原始文本: {}", e.getMessage());
        }
        return raw.trim();
    }

    private ChatSession findChatSession(String sessionId) throws Exception {
        ChatSession session = chatSessionService.findById(sessionId);
        if (session == null) {
            throw new UserException("会话不存在: " + sessionId);
        }
        return session;
    }

    /**
     * 查询会话历史
     * @return 会话历史
     */
    private List<ChatMessage> findHistoryMessages(String sessionId) throws Exception {
        return chatMessageService.getConversationHistory(sessionId);
    }

    /**
     * 处理 replace 模式：停用旧助手分支，返回历史消息到父用户消息为止
     */
    private List<ChatMessage> handleReplace(ChatMessageRequest request, WebSocketSession session) throws Exception {
        String replaceMessageId = request.getReplaceMessageId();
        if (!StringUtils.hasText(replaceMessageId)) {
            throw new UserException("replace 模式下 replaceMessageId 不能为空");
        }

        // 找到要被替换的助手消息
        ChatMessage replacedMsg = chatMessageService.findById(replaceMessageId);
        if (replacedMsg == null) {
            throw new UserException("要替换的消息不存在: " + replaceMessageId);
        }
        if (!ChatMessage.ROLE_ASSISTANT.equals(replacedMsg.getRole())) {
            throw new UserException("只能替换助手消息，当前消息角色为: " + replacedMsg.getRole());
        }

        // 验证父消息必须是用户消息
        String parentId = replacedMsg.getParentId();
        if (!StringUtils.hasText(parentId)) {
            throw new UserException("要替换的消息没有父消息");
        }
        ChatMessage parentMsg = chatMessageService.findById(parentId);
        if (parentMsg == null || (!ChatMessage.ROLE_USER.equals(parentMsg.getRole()) && !ChatMessage.ROLE_TOOL.equals(parentMsg.getRole()))) {
            throw new UserException("被替换消息的父消息必须是用户消息或工具消息");
        }

        // 停用旧的助手分支（包括所有子孙消息）
        chatMessageService.deactivateBranch(replaceMessageId);

        // 返回当前活跃的历史消息（旧分支已停用，历史会截止到父用户消息）
        return chatMessageService.getConversationHistory(request.getSessionId());
    }

    /**
     * 处理 edit 模式：停用旧用户消息分支，创建新的用户消息，返回历史消息
     * <p>仅替换文本内容，保留旧消息中的文件附件（image / video / audio / file）
     */
    private List<ChatMessage> handleEdit(ChatMessageRequest request) throws Exception {
        String editMessageId = request.getEditMessageId();
        if (!StringUtils.hasText(editMessageId)) {
            throw new UserException("edit 模式下 editMessageId 不能为空");
        }

        // 找到要被编辑的用户消息
        ChatMessage oldUserMsg = chatMessageService.findById(editMessageId);
        if (oldUserMsg == null) {
            throw new UserException("要编辑的消息不存在: " + editMessageId);
        }
        if (!ChatMessage.ROLE_USER.equals(oldUserMsg.getRole())) {
            throw new UserException("只能编辑用户消息，当前消息角色为: " + oldUserMsg.getRole());
        }

        // 提取旧消息中的非文本内容（文件附件），编辑时保留
        List<MessageContent> preservedContents = new ArrayList<>();
        if (oldUserMsg.getContents() != null) {
            for (MessageContent c : oldUserMsg.getContents()) {
                if (!(c instanceof TextContent)) {
                    preservedContents.add(c);
                }
            }
        }

        // 获取旧用户消息的 parentId（可能为 null，表示根消息）
        String parentId = oldUserMsg.getParentId();

        // 停用旧的用户消息分支（包括所有子孙消息）
        chatMessageService.deactivateBranch(editMessageId);

        ChatMessage chatMessage = new ChatMessage()
                .user(request.getContent(), preservedContents)
                .makeInsertable(request.getSessionId(), parentId, userSettings.getUsername());

        try {
            ChatMessage message = chatMessageService.save(chatMessage);
            ChatResponse response = new ChatResponse()
                    .setMessages(List.of(message))
                    .setStatus(ChatResponse.STATUS_INIT_USER)
                    .setSessionId(request.getSessionId());
            sessionMessageBus.publish(request.getSessionId(), objectMapper.writeValueAsString(response));
        } catch (UserException e) {
            throw new UserException("保存用户消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存用户消息失败: " + e.getMessage());
        }

        // 返回当前活跃的历史消息（包括新创建的用户消息）
        return chatMessageService.getConversationHistory(request.getSessionId());
    }

    /**
     * 添加用户消息（含文件附件）
     *
     * @param sessionId 会话 ID
     * @param parentId  父消息 ID
     * @param prompt    用户输入的文本
     * @param fileUrls  文件引用路径列表（绝对路径），用于前端通过 /file/proxy 获取
     */
    private ChatMessage appendUserMessage(String sessionId, String parentId, String prompt,
                                          List<String> fileUrls) throws Exception {

        if (StringUtils.hasText(parentId)) {
            ChatMessage lastMessage = chatMessageService.findById(parentId);
            if (ChatMessage.ROLE_TOOL.equals(lastMessage.getRole())) {
                ChatMessage paddingAssistant = new ChatMessage()
                        .assistant("", "", List.of())
                        .makeInsertable(sessionId, lastMessage.getParentId(), userSettings.getUsername());
                ChatMessage saved = chatMessageService.save(paddingAssistant);
                parentId = saved.getId();
            }
            if (ChatMessage.ROLE_ASSISTANT.equals(lastMessage.getRole()) && !CollectionUtils.isEmpty(lastMessage.getToolCalls())) {
                ChatMessage fixAssistant = new ChatMessage();
                BeanUtils.copyProperties(lastMessage, fixAssistant);
                fixAssistant.setToolCalls(List.of());
                chatMessageService.replace(fixAssistant);
            }
        }
        List<MessageContent> fileContents = MessageContent.files(fileUrls);
        ChatMessage chatMessage = new ChatMessage()
                .user(prompt, fileContents)
                .makeInsertable(sessionId, parentId, userSettings.getUsername());

        try {
            ChatMessage message = chatMessageService.save(chatMessage);
            ChatResponse response = new ChatResponse().afterUserInput(message);
            sessionMessageBus.publish(sessionId, objectMapper.writeValueAsString(response));
            return message;
        } catch (UserException e) {
            throw new UserException("保存用户消息失败: " + e.getMessage());
        } catch (Exception e) {
            throw new Exception("保存用户消息失败: " + e.getMessage());
        }
    }

    /**
     * 将 base64 编码的文件数据写入会话目录，保留原始文件名
     * <p>命名规则: {index}_{原始文件名}；若无原始文件名则回退为 {index}.{ext}
     *
     * @param files       文件数据列表
     * @param chatSession 会话对象
     * @return 写入成功的文件绝对路径列表，用于前端 /file/proxy 代理获取
     */
    private List<String> writeSessionFile(List<FileData> files, ChatSession chatSession) {
        List<String> writtenPaths = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return writtenPaths;
        }
        if (!StringUtils.hasText(basePath)) {
            basePath = System.getProperty("user.dir") + "/session";
        }
        String dirPath = basePath + "/" + chatSession.getId() + "/file";
        File dir = new File(dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            log.error("创建目录失败: {}", dirPath);
            return writtenPaths;
        }
        for (int i = 0; i < files.size(); i++) {
            FileData fileData = files.get(i);
            if (fileData == null || !StringUtils.hasText(fileData.getData())) {
                log.warn("文件数据为空，跳过索引: {}", i);
                continue;
            }
            String dataUri = fileData.getData();
            byte[] data = Base64Utils.decodeBase64FromDataUri(dataUri);
            if (data == null) {
                log.warn("无法解析文件数据，跳过索引: {}", i);
                continue;
            }

            String fileName;
            if (StringUtils.hasText(fileData.getName())) {
                fileName = i + "_" + fileData.getName();
            } else {
                String extension = Base64Utils.getExtensionFromDataUri(dataUri);
                fileName = i + "." + extension;
            }

            try {
                java.nio.file.Path filePath = Paths.get(dirPath, fileName);
                Files.write(filePath, data);
                writtenPaths.add(filePath.toAbsolutePath().toString());
            } catch (IOException e) {
                log.error("写入文件失败 [{}]: {}", i, e.getMessage());
            }
        }
        return writtenPaths;
    }
}
