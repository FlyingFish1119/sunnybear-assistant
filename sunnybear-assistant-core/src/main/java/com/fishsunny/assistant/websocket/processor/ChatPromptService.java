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
import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.service.mcp.McpClientService;
import com.fishsunny.assistant.mvc.service.ChatProjectBindings;
import com.fishsunny.assistant.mvc.service.ChatProjectService;
import com.fishsunny.assistant.mvc.service.CronJobService;
import com.fishsunny.assistant.mvc.service.KnowledgeService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.McpSettings;
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
    private final CronJobService cronJobService;
    private final McpClientService mcpClientService;

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

        injectCronPrompt(chatSession, systemPrompt);
        injectProjectPrompt(chatSession, systemPrompt);
        injectKnowledgePrompt(originMessages, chatSession, systemPrompt, session);
        injectMemoryPrompt(systemPrompt);
        injectMcpPrompt(systemPrompt);

        return systemPrompt.toString();
    }

    /**
     * 定时任务会话提示。
     * <p>判定只看会话本身（{@code cronId} 非空即由定时任务开出来的会话），不往消息上打任何标记。
     * 真正的区分放在提示词里交给模型：第一条消息是定时器写的、没人现场提问，后面若还有消息，
     * 那就是用户本人回来接着谈这件事了。模型看得见完整历史，这件事它自己判得出来。
     * <p>顺带带上任务的 meta（标题 / cron / 描述），模型才晓得这一趟跑的是哪件事。
     * 任务可能已被删而执行会话还留着，这时只降级成「定时任务会话」，不阻断对话。
     * <p>文案一律用中性称呼（用户），不写任何角色设定的称呼 —— 这段是通用提示词，
     * 不该掺进某个助手的人格。
     */
    private void injectCronPrompt(ChatSession chatSession, StringBuilder systemPrompt) {
        if (chatSession == null || chatSession.getCronId() == null) {
            return;
        }
        CronJob job = findCronJob(chatSession.getCronId());
        if (job == null) {
            throw new RuntimeException("定时任务信息读取失败: cronId=" + chatSession.getCronId());
        }

        String title = StringUtils.hasText(job.getTitle()) ? job.getTitle() : "无标题";
        String cron = StringUtils.hasText(job.getCron()) ? job.getCron() : "ERROR";
        String description = StringUtils.hasText(job.getDescription()) ? job.getDescription() : "无描述";
        String template = """
                \n[cron]
                这个会话不是用户实时跟你对话开出来的，而是定时任务 **${title}** 到点自动建立的。
                任务信息：cron `**${cron}**`；描述：**${description}**。
                会话里的第一条用户消息由系统定时器写入，写的时候没人现场提问，也没人在等着你立刻回话。
                处理这第一条消息时不要摆出对话姿态：
                  1. 不要寒暄开场，不要反问澄清，不要把话头抛回去等回复，也不要写“需要我再做点什么吗”这类等人接话的收尾；
                  2. 把消息里的要求当成一个独立任务，一次做完，直接给出完整、可读的结果，结论写全，让人事后自己来看也能看懂。
                如果这个会话里出现了第一条之后的消息，那就是用户本人回来接着跟你聊这件事了：那时候按正常对话对待。
                """.replace("${title}", title)
                .replace("${cron}", cron)
                .replace("${description}", description);
        systemPrompt.append(template);
    }

    /** 任务可能已被删掉而历史执行会话还留着：查不到、或查库出岔子都返回 null，调用方降级 */
    private CronJob findCronJob(Integer cronId) {
        try {
            return cronJobService.findById(cronId);
        } catch (Exception e) {
            log.warn("定时任务信息读取失败: cronId={}, {}", cronId, e.getMessage());
            throw new RuntimeException("定时任务信息读取失败: cronId=" + cronId, e);
        }
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

    /**
     * MCP Server 清单注入。
     * <p>只给「接了哪些 MCP、各自是干嘛的」，不罗列具体工具名与入参 Schema ——
     * 工具明细由 mcp_list_tool 现查，避免开局就把一堆 schema 塞进上下文。
     * <p>文案用中性称呼（用户），不掺任何角色设定的人格称呼。
     */
    private void injectMcpPrompt(StringBuilder systemPrompt) {
        try {
            List<McpSettings.Client> clients = mcpClientService.clients();
            if (clients == null || clients.isEmpty()) {
                return;
            }
            StringBuilder section = new StringBuilder("\n[mcp]\n");
            section.append("已接入以下 MCP Server，需要具体工具清单时用 mcp_list_tool 查询：\n");
            for (McpSettings.Client client : clients) {
                section.append("- ").append(client.getServerName());
                if (StringUtils.hasText(client.getDesc())) {
                    section.append("：").append(client.getDesc());
                }
                section.append("\n");
            }
            systemPrompt.append(section);
        } catch (Exception e) {
            log.warn("MCP Server 清单注入失败: {}", e.getMessage());
        }
    }
}
