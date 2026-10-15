package com.fishsunny.assistant.websocket.request.handler;

/*
 * @Usage
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/28 19:33
 */

import com.fishsunny.assistant.dto.ChatMessageRequest;
import com.fishsunny.assistant.engine.jev.JevClient;
import com.fishsunny.assistant.engine.jev.JevResponseMapper;
import com.fishsunny.assistant.engine.jev.request.JevRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.MessageContent;
import com.fishsunny.assistant.engine.protocol.project.entity.message.content.file.FileContent;
import com.fishsunny.assistant.exception.UserException;
import com.fishsunny.assistant.mvc.service.ChatProjectBindings;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.mvc.service.CronJobService;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.websocket.request.*;
import com.fishsunny.assistant.websocket.ws.SessionMessageBus;
import com.fishsunny.assistant.websocket.ws.SynchronizedWebSocketSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class CreateChatMessageRequestHandler implements ChatMessageRequestHandler {

    /** 等 Jev 项目判定结果的上限：超时按「没判中」处理，不拖住会话创建 */
    private static final long JUDGE_TIMEOUT_SECONDS = 5;

    private final ChatSessionService chatSessionService;
    private final CronJobService cronJobService;
    private final SessionFileWriter sessionFileWriter;
    private final UserMessageAppender userMessageAppender;
    private final UserSettings userSettings;
    private final SessionMessageBus sessionMessageBus;
    private final JevClient jevClient;
    private final ChatProjectService chatProjectService;

    @Override
    public ChatSessionModeParseResult handle(ChatMessageRequest request, SynchronizedWebSocketSession safeSession, ChatMessageRequestProvider provider) throws Exception {
        ChatSession chatSession;
        // cron 触发：通过 cronId 查库获取标题，session type = 'cron'
        if (request.getCronId() != null) {
            chatSession = createCronChatSession(request.getCronId());
        } else {
            // 项目归属判定与模型档位判定各自都要等一次 Jev 往返：项目判定丢进虚拟线程，跟串行流程并发跑
            FutureTask<ChatProject> projectTask = judgeProjectTask(request);
            // 前端可显式指定新会话的模型档位（发送区按钮）；未指定时按「自动切换模型」判断复杂度
            boolean enablePro;
            if (request.getForcePro() != null) {
                enablePro = request.getForcePro();
            } else if (Boolean.TRUE.equals(provider.getEnableProModel().get())) {
                enablePro = judgeProModel(request.getContent());
            } else {
                enablePro = false;
            }
            chatSession = createChatSession(request, enablePro, provider.getSessionType().get());
            // 判中项目就顺手归入：会话带上 project_id 之后，下面挂项目文档那一步自然生效
            chatSession = attachJudgedProject(chatSession, projectTask);
        }
        sessionMessageBus.subscribeExclusive(chatSession.getId(), safeSession.delegate());
        request.setSessionId(chatSession.getId());
        // 先写文件，再创建带文件引用的用户消息；项目会话的第一条消息额外挂上自动追加的文件
        List<String> createFileUrls = sessionFileWriter.writeSessionFile(request.getFiles(), chatSession);
        List<MessageContent> messageContents = autoAppendContents(chatSession);
        ChatMessage newMessage = userMessageAppender.appendUserMessage(chatSession.getId(), null, request.getContent(),
                createFileUrls, messageContents);
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(newMessage);
        return new ChatSessionModeParseResult(chatSession, true, messages);
    }

    @Override
    public String getMode() {
        return ChatMessageRequest.MODE_CREATE;
    }

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
        chatSession.setCronId(cronJob.getId());
        chatSession.setEnablePro(cronJob.getEnablePro() != null && cronJob.getEnablePro());
        chatSession.setUnreviewed(cronJob.getUnreviewed() != null && cronJob.getUnreviewed());
        try {
            return chatSessionService.save(chatSession);
        } catch (Exception e) {
            log.error("Error create cron chat session: {}", e.getMessage());
            throw new UserException("创建 cron 会话失败: " + e.getMessage());
        }
    }

    /**
     * 项目会话第一条用户消息额外挂的内容块：项目「自动追加」清单里每个文件的绝对路径。
     * <p>只在新建会话这一条消息上挂：这些都是活文件，模型每轮都会按路径读到最新内容，
     * 后续消息不必重复挂。散会话 / cron 会话解析不出 project_id、清单为空、文件已被删，
     * 都返回空，消息照常发。
     */
    private List<MessageContent> autoAppendContents(ChatSession chatSession) {
        String projectId = ChatProjectBindings.resolveProjectId(chatSession);
        if (!StringUtils.hasText(projectId)) {
            return List.of();
        }
        List<String> paths = chatProjectService.listAutoAppendPaths(projectId);
        if (CollectionUtils.isEmpty(paths)) {
            return List.of();
        }
        List<MessageContent> contents = new ArrayList<>(paths.size());
        for (String path : paths) {
            contents.add(new FileContent(path));
        }
        return contents;
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

    /**
     * 起一个虚拟线程跑项目归属判定；不需要判的时候返回 null。
     * <p>三种不判的情况：主人没开自动归入；新会话已经自带项目（主人的指定优先，不再猜）；
     * 消息没有文本（纯附件）。结果由 {@link #attachJudgedProject} 取用。
     */
    private FutureTask<ChatProject> judgeProjectTask(ChatMessageRequest request) {
        if (!Boolean.TRUE.equals(userSettings.getEnableAutoMatchProject())) {
            return null;
        }
        if (!CollectionUtils.isEmpty(request.getExtension())
                && StringUtils.hasText(String.valueOf(request.getExtension().get(ChatProjectBindings.EXTENSION_KEY)))) {
            return null;
        }
        if (!StringUtils.hasText(request.getContent())) {
            return null;
        }
        FutureTask<ChatProject> task = new FutureTask<>(() -> judgeProject(request.getContent()));
        Thread.ofVirtual().name("judge-project").start(task);
        return task;
    }

    /**
     * 取并发判定的结果：判中就把会话归入该项目（库里 extension 落上 project_id）。
     * <p>判不中、超时、判定抛异常，都只是没有项目归属，不影响会话创建 —— 后面照常按散会话走。
     *
     * @return 带上项目归属的会话；没判中时原样返回
     */
    private ChatSession attachJudgedProject(ChatSession chatSession, FutureTask<ChatProject> task) {
        if (task == null) {
            return chatSession;
        }
        try {
            ChatProject project = task.get(JUDGE_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (project == null) {
                return chatSession;
            }
            ChatSession bound = chatProjectService.attachSession(chatSession.getId(), project.getId());
            log.info("新会话 [{}] 判中项目「{}」并自动归入", chatSession.getId(), project.getName());
            return bound;
        } catch (Exception e) {
            log.warn("项目归属判定未生效: {}", e.getMessage());
            return chatSession;
        }
    }

    /**
     * 让 Jev 判这条消息属不属于某个项目：把全部项目的名称与描述各提一个 noul 问题
     * （「这句话是不是这个项目里的事」），一次请求全塞进去，由服务端并行作答；
     * 命中概率大于 user_settings 里的项目阈值才算命中，多个命中取概率最高的那个。
     * <p>与知识库挑条目用的是同一套写法（见 KnowledgeServiceImplement#selectKnowledgeByJev）。
     *
     * @return 判中的项目；一个项目都没有、没命中、或调用失败时返回 null
     */
    private ChatProject judgeProject(String content) {
        try {
            List<ChatProject> projects = chatProjectService.listAll();
            if (CollectionUtils.isEmpty(projects)) {
                return null;
            }
            JevRequest.Builder builder = JevRequest.Builder.create(content);
            for (ChatProject project : projects) {
                String description = StringUtils.hasText(project.getDescription())
                        ? project.getDescription() : "no description yet";
                builder.noulQuestion(
                        project.getId(),
                        "Is the user's current message about the project \"" + project.getName()
                                + "\" (" + description + ")?",
                        "The message continues this project's work, asks about it, or clearly belongs to it.",
                        "The message has nothing to do with this project."
                );
            }
            JevResponseMapper mapper = jevClient.send(builder.build());
            double threshold = userSettings.getProjectMatchThreshold();
            ChatProject hit = null;
            double hitProbability = threshold;
            for (ChatProject project : projects) {
                Double probability = mapper.mappingNoul(project.getId());
                if (probability != null && probability > hitProbability) {
                    hit = project;
                    hitProbability = probability;
                }
            }
            return hit;
        } catch (Exception e) {
            log.warn("Jev 项目归属判定失败: {}", e.getMessage());
            return null;
        }
    }
}
