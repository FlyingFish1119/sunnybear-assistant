package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage WebSocket 消息业务处理器 —— 串联 消息持久化 + AI 调用 + 流式响应
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27
 */

import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.dto.FileData;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.text.TextContent;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.variable.RoleVariable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 本类主要和元数据处理逻辑有关
 */
@Component
public class ServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(ServiceProcessor.class);

    @Value("${assistant.file.base-path:}")
    private String basePath;

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final ObjectMapper objectMapper;
    private final UserSettings userSettings;
    private final AssistantSettings assistantSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings titleAISettings;
    private final AISettings missionAISettings;
    public ServiceProcessor(ChatMessageService chatMessageService,
                            ChatSessionService chatSessionService,
                            ObjectMapper objectMapper,
                            UserSettings userSettings,
                            AssistantSettings assistantSettings,
                            ChatHttpHandler chatHttpHandler,
                            @Qualifier(AISettings.TITLE) AISettings titleAISettings,
                            @Qualifier(AISettings.MISSION) AISettings missionAISettings
                            ) {
        this.chatMessageService = chatMessageService;
        this.chatSessionService = chatSessionService;
        this.objectMapper = objectMapper;
        this.userSettings = userSettings;
        this.assistantSettings = assistantSettings;
        this.chatHttpHandler = chatHttpHandler;
        this.titleAISettings = titleAISettings;
        this.missionAISettings = missionAISettings;
    }

    /**
     * 检查请求参数
     */
    public ChatMessageRequest checkRequest(String payload) throws Exception {
        try {
            ChatMessageRequest request = objectMapper.readValue(payload, ChatMessageRequest.class);
            switch (request.getMode()) {
                case ChatMessageRequest.MODE_CREATE:
                    if (!StringUtils.hasText(request.getContent())) {
                        throw new UserException("内容为空");
                    }
                    return request;
                case ChatMessageRequest.MODE_APPEND:
                case ChatMessageRequest.MODE_REPLACE:
                case ChatMessageRequest.MODE_EDIT:
                    if (!StringUtils.hasText(request.getContent())) {
                        throw new UserException("内容为空");
                    }
                    if (!StringUtils.hasText(request.getSessionId())) {
                        throw new UserException("会话 ID 为空");
                    }
                    return request;
                default:
                    throw new UserException("无效的请求类型[" + request.getMode() + "]");
            }
        } catch (Exception e) {
            throw new UserException("消息格式无效: " + e.getMessage());
        }
    }

    /**
     * 创建会话
     * @param enablePro 是否启用高级模型
     */
    public ChatSession createChatSession(boolean enablePro) throws Exception {
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
     * 使用 mission AI 判断用户问题是否需要使用复杂模型（chat_pro）。
     * @return true = 需要高级模型，false = 普通模型即可
     */
    public boolean judgeProModel(String userQuestion) {
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
                .loadSettings(missionAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(judgmentPrompt),
                        new ChatMessage().user(userQuestion)
                ));

        try {
            AtomicBoolean result = new AtomicBoolean(false);
            chatHttpHandler.translate(
                    java.util.UUID.randomUUID().toString(),
                    missionAISettings.getAdapterName(),
                    request,
                    false,
                    null,
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

    public void generateTitle(WebSocketSession session, ChatSession chatSession, String userPrompt, String responsePrompt) throws Exception {
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
                .loadSettings(titleAISettings)
                .setMessages(List.of(
                        new ChatMessage().system(titleAISettings.getPrompt()),
                        new ChatMessage().user(prompt)
                ));
        try {
            ChatHttpHandler.CompleteCallback onComplete = (result, lastRes) -> {
                chatSession.setName(result.content());
            };
            chatHttpHandler.translate(UUID.randomUUID().toString(), titleAISettings.getAdapterName(), request,
                    titleAISettings.getStream() != null ? titleAISettings.getStream() : true,
                    null, onComplete);
            chatSessionService.update(chatSession);
            session.sendMessage(new TextMessage(ControlSign.UPDATE_SESSION + objectMapper.writeValueAsString(chatSession)));
        } catch (Exception e) {
            log.error("Error title generate: {}", e.getMessage());
        }
    }

    public ChatSession findChatSession(String sessionId) throws Exception {
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
    public List<ChatMessage> findHistoryMessages(String sessionId) throws Exception {
        return chatMessageService.getConversationHistory(sessionId);
    }

    /**
     * 处理 replace 模式：停用旧助手分支，返回历史消息到父用户消息为止
     */
    public List<ChatMessage> handleReplace(ChatMessageRequest request, WebSocketSession session) throws Exception {
        String replaceMessageId = request.getReplaceMessageId();
        if (!StringUtils.hasText(replaceMessageId)) {
            throw new UserException("replace 模式下 replaceMessageId 不能为空");
        }

        // 找到要被替换的助手消息
        ChatMessage replacedMsg = chatMessageService.findById(replaceMessageId);
        if (replacedMsg == null) {
            throw new UserException("要替换的消息不存在: " + replaceMessageId);
        }
        if (!RoleVariable.ROLE_ASSISTANT.equals(replacedMsg.getRole())) {
            throw new UserException("只能替换助手消息，当前消息角色为: " + replacedMsg.getRole());
        }

        // 验证父消息必须是用户消息
        String parentId = replacedMsg.getParentId();
        if (!StringUtils.hasText(parentId)) {
            throw new UserException("要替换的消息没有父消息");
        }
        ChatMessage parentMsg = chatMessageService.findById(parentId);
        if (parentMsg == null || (!RoleVariable.ROLE_USER.equals(parentMsg.getRole()) && !RoleVariable.ROLE_TOOL.equals(parentMsg.getRole()))) {
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
    public List<ChatMessage> handleEdit(ChatMessageRequest request, WebSocketSession session) throws Exception {
        String editMessageId = request.getEditMessageId();
        if (!StringUtils.hasText(editMessageId)) {
            throw new UserException("edit 模式下 editMessageId 不能为空");
        }

        // 找到要被编辑的用户消息
        ChatMessage oldUserMsg = chatMessageService.findById(editMessageId);
        if (oldUserMsg == null) {
            throw new UserException("要编辑的消息不存在: " + editMessageId);
        }
        if (!RoleVariable.ROLE_USER.equals(oldUserMsg.getRole())) {
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

        // 创建新的用户消息：新文本 + 保留的旧文件附件
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setName(userSettings.getUsername());
        chatMessage.setRole(RoleVariable.ROLE_USER);
        chatMessage.setSessionId(request.getSessionId());
        chatMessage.setParentId(parentId);
        List<MessageContent> contents = new ArrayList<>();
        contents.add(new TextContent(request.getContent()));
        contents.addAll(preservedContents);
        chatMessage.setContents(contents);

        try {
            ChatMessage message = chatMessageService.save(chatMessage);
            ChatResponse response = new ChatResponse()
                    .setMessages(List.of(message))
                    .setStatus(ChatResponse.STATUS_INIT_USER)
                    .setSessionId(request.getSessionId());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
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
     * @param session   WebSocket 会话
     */
    public ChatMessage appendUserMessage(String sessionId, String parentId, String prompt,
                                          List<String> fileUrls, WebSocketSession session) throws Exception {

        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setName(userSettings.getUsername());
        chatMessage.setRole(RoleVariable.ROLE_USER);
        chatMessage.setSessionId(sessionId);
        chatMessage.setParentId(parentId);
        List<MessageContent> contents = new ArrayList<>();
        contents.add(new TextContent(prompt));

        // 将文件附加到消息中
        List<MessageContent> fileContents = MessageContent.loadFileContent(fileUrls);
        contents.addAll(fileContents);

        chatMessage.setContents(contents);

        try {
            ChatMessage message = chatMessageService.save(chatMessage);
            ChatResponse response = new ChatResponse()
                    .setMessages(List.of(message))
                    .setStatus(ChatResponse.STATUS_INIT_USER)
                    .setSessionId(sessionId);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(response)));
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
    public List<String> writeSessionFile(List<FileData> files, ChatSession chatSession) {
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
