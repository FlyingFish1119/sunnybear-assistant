package com.fishsunny.assistant.websocket.processor;

/*
 * @Usage 默认系统提示词构造服务：变量替换 + 知识库/记忆注入
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/24
 */

import com.fishsunny.assistant.constants.ControlSign;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatProjectBindings;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.MemorySettings;
import com.fishsunny.assistant.utils.ObjectUtils;
import com.fishsunny.assistant.utils.ProjectFileManager;
import com.fishsunny.assistant.websocket.provider.ChatProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.InetAddress;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.List;
import java.util.Locale;

/**
 * 负责 ChatProcessor 默认系统提示词的构造，包括变量替换与知识库、记忆注入。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ChatPromptService {

    public static final String CURRENT_TIME = "${current_time}";
    public static final String CURRENT_DATETIME = "${current_datetime}";
    public static final String MODEL_NAME = "${model_name}";
    public static final String IP_ADDRESS = "${ip_address}";
    public static final String OPERATING_SYSTEM = "${operation_system}";
    public static final String SESSION_ID = "${session_id}";
    public static final String TOOL_NOTE = "${tool_note}";

    private static final String TOOL_NOTE_CONTENT = """
    所有的工具都能并发调用且不会互相冲突，但不保证工具调用的顺序
    """;

    private final KnowledgeService knowledgeService;
    private final MemoryService memoryService;
    private final MemorySettings memorySettings;
    private final ChatProjectService chatProjectService;
    private final ProjectFileManager projectFileManager;

    public String defaultSystemPrompt(ChatProvider.SystemProviderContext context, AssistantSettings activeAssistantSettings, String effectiveModelName, WebSocketSession session) throws Exception {
        ChatSession chatSession = context.chatSession();
        List<ChatMessage> originMessages = context.originMessages();

        // 替换变量（系统提示词使用助手设定，模型名使用实际生效的模型）
        String dayOfWeek = LocalDate.now().getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.CHINA);
        StringBuilder systemPrompt = new StringBuilder(activeAssistantSettings.getPrompt()
                .replace(CURRENT_TIME, LocalDate.now() + " " + dayOfWeek)
                .replace(CURRENT_DATETIME, LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00")) + " " + dayOfWeek)
                .replace(MODEL_NAME, effectiveModelName)
                .replace(IP_ADDRESS, InetAddress.getLocalHost().toString())
                .replace(OPERATING_SYSTEM, System.getProperty("os.name") + " " + System.getProperty("os.version"))
                .replace(SESSION_ID, chatSession.getId())
                .replace(TOOL_NOTE, TOOL_NOTE_CONTENT)
        );

        injectProjectPrompt(chatSession, systemPrompt);
        injectKnowledgePrompt(originMessages, chatSession, systemPrompt, session);
        injectMemoryPrompt(systemPrompt);

        return systemPrompt.toString();
    }

    /**
     * 项目文档目录注入。
     * <p>项目会话（extension 里挂着 chat_project_id）在系统提示词末尾带一段项目背景：
     * 项目名 + 资料目录绝对路径 + 入口文档名。
     * <p>只给「文档放在哪里」，不把内容读出来塞进上下文 ——
     * 项目资料是跟着项目走的活文件，每轮都可能被改，让模型需要时自己用文件工具读，
     * 才不会拿着一份开局快照说事。
     */
    private void injectProjectPrompt(ChatSession chatSession, StringBuilder systemPrompt) {
        String projectId = ChatProjectBindings.resolveProjectId(chatSession);
        if (!StringUtils.hasText(projectId)) {
            return;
        }
        try {
            // 项目记录已不在（删除过程中的中间态）时不注入：不该把模型引向一个无主目录
            ChatProject project = chatProjectService.findById(projectId);
            if (project == null) {
                return;
            }
            // 绝对路径：base-path 配置是相对路径（data/），原样给模型会依赖工作目录，读起来不确定
            String projectDir = projectFileManager.buildProjectDirPath(projectId)
                    .toAbsolutePath().normalize().toString();
            systemPrompt.append("\n[project]\n")
                    .append("当前会话属于项目「").append(project.getName()).append("」，")
                    .append("该项目的资料目录：`").append(projectDir).append("`\n");
        } catch (Exception e) {
            log.warn("项目文档目录注入失败: projectId={}, {}", projectId, e.getMessage());
        }
    }

    private void injectKnowledgePrompt(List<ChatMessage> originMessages, ChatSession chatSession, StringBuilder systemPrompt, WebSocketSession session) {
        // 知识库注入：依据用户最新消息自动挑选相关知识条目注入上下文
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
            session.sendMessage(new TextMessage(ControlSign.SIGN_KNOWLEDGE_HIT + chatSession.getId()));
        } catch (Exception e) {
            log.warn("知识库匹配失败: {}", e.getMessage());
        }
    }

    private void injectMemoryPrompt(StringBuilder systemPrompt) {
        // 记忆注入开关：关闭后对话不再注入记忆（不影响记忆 CRUD 与问候语个性化）
        if (!Boolean.TRUE.equals(memorySettings.getEnable())) {
            return;
        }
        try {
            String memorySection = memoryService.buildMemorySection();
            if (StringUtils.hasText(memorySection)) {
                systemPrompt.append(memorySection);
            }
        } catch (Exception e) {
            log.warn("核心记忆注入失败: {}", e.getMessage());
        }
    }
}
