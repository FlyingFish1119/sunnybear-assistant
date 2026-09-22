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
import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.dto.FileData;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.jev.JevClient;
import com.fishsunny.assistant.engine.jev.request.JevRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.ChatResponse;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.tool.service.background.BackgroundToolResponseBus;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.mvc.service.CronJobService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.utils.Base64Utils;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.SessionFileManager;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import com.fishsunny.assistant.websocket.SynchronizedWebSocketSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 本类主要和元数据处理逻辑有关
 */
@Component
public class ServiceProcessor {

    private static final Logger log = LoggerFactory.getLogger(ServiceProcessor.class);

    /** 会话附件存储文件名的毫秒级时间戳格式 */
    private static final DateTimeFormatter SESSION_FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmssSSS");

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

    private final ChatMessageService chatMessageService;
    private final ChatSessionService chatSessionService;
    private final CronJobService cronJobService;
    private final ObjectMapper objectMapper;
    private final UserSettings userSettings;
    private final AssistantSettings assistantSettings;
    private final ChatHttpHandler chatHttpHandler;
    private final AISettings cubAISettings;
    private final SessionMessageBus sessionMessageBus;
    private final SessionFileManager sessionFileManager;
    private final BackgroundToolResponseBus backgroundToolResponseBus;
    private final JevClient jevClient;
    public ServiceProcessor(ChatMessageService chatMessageService,
                            ChatSessionService chatSessionService,
                            CronJobService cronJobService,
                            ObjectMapper objectMapper,
                            UserSettings userSettings,
                            AssistantSettings assistantSettings,
                            ChatHttpHandler chatHttpHandler,
                            @Qualifier(AISettings.CUB) AISettings cubAISettings,
                            SessionMessageBus sessionMessageBus,
                            SessionFileManager sessionFileManager,
                            BackgroundToolResponseBus backgroundToolResponseBus,
                            JevClient jevClient
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
        this.sessionFileManager = sessionFileManager;
        this.backgroundToolResponseBus = backgroundToolResponseBus;
        this.jevClient = jevClient;
    }

    /**
     * 解析一次 WS 消息：按 mode 创建/加载会话、落库用户消息并推送 init_user。
     *
     * @param request        消息请求
     * @param safeSession    线程安全包装的 WS 连接
     * @param isEnablePro    是否允许本轮启用高级模型
     * @param sessionType    MODE_CREATE 新建插件会话时的类型打标（如 character/world）；null = 普通会话。由连接端点决定，核心层不感知语义
     */
    public ChatSessionModeParseResult handleChatSession(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, boolean isEnablePro, String sessionType) throws Exception {
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
                    chatSession = createChatSession(request, enablePro, sessionType);
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
     * 创建普通会话。
     * <p>插件（角色/世界）会话的一次性注册在此完成：create 消息携带的 extension（如 {"characterId":...}/{"worldId":...}）
     * 直接序列化进 ChatSession.extension，sessionType 由连接的 WS 端点提供（插件 handler 打标），
     * 前端无需再二次调用 bind 接口 —— 服务端随后按 type + extension 即可匹配到该资源下的会话。
     *
     * @param request     消息请求（可能携带 extension 扩展字段，语义由各插件约定）
     * @param enablePro   是否启用高级模型
     * @param sessionType 会话类型；null 表示核心普通会话（保持默认 'chat'）
     */
    private ChatSession createChatSession(ChatMessageRequest request, boolean enablePro, String sessionType) throws Exception {
        ChatSession chatSession = new ChatSession("新会话");
        chatSession.setEnablePro(enablePro);
        if (StringUtils.hasText(sessionType)) {
            chatSession.setType(sessionType);
        }
        if (!CollectionUtils.isEmpty(request.getExtension())) {
            chatSession.setExtension(new java.util.LinkedHashMap<>(request.getExtension()));
        }
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
        chatSession.setUnreviewed(cronJob.getUnreviewed() != null && cronJob.getUnreviewed());
        try {
            return chatSessionService.save(chatSession);
        } catch (Exception e) {
            log.error("Error create cron chat session: {}", e.getMessage());
            throw new UserException("创建 cron 会话失败: " + e.getMessage());
        }
    }

    private boolean judgeProModel(String userQuestion) {
        if (userSettings.getEnableAutoSwitchModel() == null || !userSettings.getEnableAutoSwitchModel()) {
            return false;
        }
        if (!StringUtils.hasText(userQuestion)) {
            return false;
        }

        try {
            JevRequest jevRequest = JevRequest.Builder.create(userQuestion)
                    .noulQuestion(
                            "complex_question",
                            "Is the user's question a complex question, or one that will lead to a complex task?",
                            "Questions that are inherently complex (deep reasoning, multi-step logic, domain expertise, complex code analysis), or that will likely spawn a subsequent complex task (multi-file code changes, system-level operations, long multi-step workflows).",
                            "Casual greetings, simple factual Q&A, basic translation, format conversion, or other questions that are neither complex themselves nor likely to lead to a complex task."
                    )
                    .build();
            Double needProModel = jevClient.send(jevRequest).mappingNoul("complex_question");
            return needProModel != null && needProModel > userSettings.getProModelThreshold();
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

        ChatRequest request = new ChatRequest().quickJsonBuild(TITLE_PROMPT, prompt, cubAISettings);
        try {
            ChatHttpHandler.TranslateData translateData = new ChatHttpHandler.TranslateData(
                    UUID.randomUUID().toString(), cubAISettings.getAdapterName(), request
            );
            ChatHttpHandler.TranslateHandler translateHandler = new ChatHttpHandler.TranslateHandler()
                    .complete((result, lastRes) -> chatSession.setName(parseTitle(result.content())));
            ChatHttpHandler.TranslateOption translateOption = new ChatHttpHandler.TranslateOption()
                    .setStream(cubAISettings.getStream())
                    .setEnableTTS(false);

            chatHttpHandler.translate(translateData, translateHandler, translateOption);
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
     * replace 失败回滚：把 {@link #handleReplace} 停用的旧助手分支重新点亮。
     * <p>
     * 停用是落库动作，一旦本轮生成失败（建连报错/超时/流中断），活跃链就永远断在父消息上 ——
     * 父消息是 tool 时链尾变成 tool，用户后续的消息会顺势挂到它后面，坏状态就此固化。
     * 这里把状态修回去，让「重开这一轮」失败后表现得像什么都没发生过。
     * <p>
     * 仅在父消息下已无活跃子消息时才回滚：有活跃子说明新助手回复已经落库，
     * 此时再点亮旧分支会让同一层出现两条活跃助手消息，历史出现分叉，是更糟的坏状态
     * （覆盖「工具链跑到一半才失败」这种已有产出的情况）。
     * <p>
     * 本方法自行吞掉异常：它跑在失败路径上，不能反过来盖掉原始错误。
     *
     * @param request   本轮请求（仅 replace 模式生效）
     * @param sessionId 会话 ID
     */
    public void rollbackReplacedBranch(ChatMessageRequest request, String sessionId) {
        if (request == null || !ChatMessageRequest.MODE_REPLACE.equals(request.getMode())) {
            return;
        }
        String replaceMessageId = request.getReplaceMessageId();
        if (!StringUtils.hasText(replaceMessageId) || !StringUtils.hasText(sessionId)) {
            return;
        }
        try {
            ChatMessage replaced = chatMessageService.findById(replaceMessageId);
            if (replaced == null || Boolean.TRUE.equals(replaced.getActive())) {
                // 消息不存在，或它还是活跃的（本轮压根没走到停用那一步）—— 无需回滚
                return;
            }
            String parentId = replaced.getParentId();
            boolean hasActiveSibling = chatMessageService.findBySessionId(sessionId).stream()
                    .anyMatch(m -> parentId != null && parentId.equals(m.getParentId())
                            && Boolean.TRUE.equals(m.getActive()));
            if (hasActiveSibling) {
                return;
            }
            int affected = chatMessageService.reactivateBranch(replaceMessageId);
            log.warn("replace 失败，旧助手分支已回滚: sessionId={}, replaceMessageId={}, 恢复 {} 行",
                    sessionId, replaceMessageId, affected);
        } catch (Exception e) {
            log.error("回滚 replace 旧助手分支失败: sessionId={}, replaceMessageId={}, {}",
                    sessionId, replaceMessageId, e.getMessage(), e);
        }
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

        // 编辑只替换正文（首块）：首块之后的全部内容原样带入新消息，
        // 包括文件附件与只作展示、不参与编辑的追加文本块
        List<MessageContent> preservedContents = new ArrayList<>();
        List<MessageContent> oldContents = oldUserMsg.getContents();
        if (oldContents != null && oldContents.size() > 1) {
            preservedContents.addAll(oldContents.subList(1, oldContents.size()));
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
     * @param fileUrls  文件引用列表（形如 "sessionId:fileName"），用于前端通过 /file/proxy 获取
     */
    private ChatMessage appendUserMessage(String sessionId, String parentId, String prompt,
                                          List<String> fileUrls) throws Exception {

        if (StringUtils.hasText(parentId)) {
            // 父消息是工具消息，则插入一个空的助手消息作为占位符
            ChatMessage lastMessage = chatMessageService.findById(parentId);
            if (ChatMessage.ROLE_TOOL.equals(lastMessage.getRole())) {
                ChatMessage paddingAssistant = new ChatMessage()
                        .assistant("", "", List.of())
                        .makeInsertable(sessionId, lastMessage.getParentId(), userSettings.getUsername());
                ChatMessage saved = chatMessageService.save(paddingAssistant);
                parentId = saved.getId();
            }
            // 父消息是助手消息且包含工具调用，则移除工具调用
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
        // 落库前探一次后台任务总线：上一轮遗留的在途结果作为追加文本块挂到这条用户消息末尾
        backgroundToolResponseBus.attachPending(chatMessage);

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
     * <p>命名规则: {原始文件名主干}_{时间戳}.{ext}；毫秒级时间戳保证多次上传同名文件互不覆盖，
     * 无需再探测磁盘判断编号是否已被占用。同一次上传内出现完全同名的文件时，追加序号区分。
     *
     * @param files       文件数据列表
     * @param chatSession 会话对象
     * @return 写入成功的文件引用列表（形如 "sessionId:fileName"），用于前端 /file/proxy 代理获取
     */
    private List<String> writeSessionFile(List<FileData> files, ChatSession chatSession) {
        List<String> writtenPaths = new ArrayList<>();
        if (files == null || files.isEmpty()) {
            return writtenPaths;
        }
        String sessionId = chatSession.getId();
        Set<String> usedNames = new HashSet<>();
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

            String fileName = buildSessionFileName(fileData, dataUri, usedNames);
            try {
                writtenPaths.add(sessionFileManager.writeSessionFile(sessionId, fileName, data));
            } catch (IOException e) {
                log.error("写入文件失败 [{}]: {}", i, e.getMessage());
            }
        }
        return writtenPaths;
    }

    /**
     * 构造会话目录内的存储文件名：{原始文件名主干}_{毫秒时间戳}.{ext}
     *
     * @param fileData  文件数据（含原始文件名）
     * @param dataUri   base64 data URI，用于原文件名缺失/无扩展名时兜底推断扩展名
     * @param usedNames 本次上传内已用过的文件名，撞名时追加序号
     * @return 仅含文件名（不含目录）的存储名
     */
    private String buildSessionFileName(FileData fileData, String dataUri, Set<String> usedNames) {
        String rawName = fileData.getName();
        String stem;
        String ext;
        if (StringUtils.hasText(rawName)) {
            int dot = rawName.lastIndexOf('.');
            if (dot > 0 && dot < rawName.length() - 1) {
                stem = rawName.substring(0, dot);
                ext = rawName.substring(dot + 1);
            } else {
                stem = rawName;
                ext = "";
            }
        } else {
            stem = "file";
            ext = "";
        }
        // 清洗路径与非法字符，防止夹带路径穿越或生成非法文件名
        stem = stem.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        if (!StringUtils.hasText(stem)) {
            stem = "file";
        }
        if (!StringUtils.hasText(ext)) {
            ext = Base64Utils.getExtensionFromDataUri(dataUri);
        }

        String ts = LocalDateTime.now().format(SESSION_FILE_TIME);
        String candidate = stem + "_" + ts + "." + ext;
        int seq = 2;
        while (!usedNames.add(candidate)) {
            candidate = stem + "_" + ts + "_" + seq + "." + ext;
            seq++;
        }
        return candidate;
    }
}
