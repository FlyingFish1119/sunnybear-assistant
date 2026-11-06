package com.fishsunny.assistant.task;

/*
 * @Usage 自动记忆整理服务
 *
 *        一段 chat 会话静默到期后，用「完整助手系统提示词 + 整段历史 + 一条临时指令」再问自己一次：
 *        这段交流里有什么值得长期记住的？结果按 add / update / delete 写进核心记忆。
 *
 *        - 系统提示词走 ChatPromptService，与正常对话同源（人设、项目背景、已有记忆都在），
 *          是「助手用自己的人格回顾对话」，不是中立摘要器。
 *        - 历史原样发，只把图片/附件换成文字占位（不展开 data URI，也避开各协议内容块支持的差异）。
 *        - 不碰会话本身：不追加消息、不落库、不推前端。
 *        - 待办表是「跑完即删」的：认领靠删行，失败不重试，过程只写日志。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.engine.protocol.project.entity.SessionMemoryJob;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.dao.SessionMemoryJobRepository;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.mvc.service.MemoryService;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.MemorySettings;
import com.fishsunny.assistant.utils.JsonUtils;
import com.fishsunny.assistant.utils.SessionFileManager;
import com.fishsunny.assistant.websocket.processor.ChatPromptService;
import com.fishsunny.assistant.websocket.provider.ChatProvider;
import com.fishsunny.assistant.websocket.ws.NoOpWebSocketSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
@Service
public class MemoryRecordService {

    /** 删除明细进日志时的截断长度（仅用于"模型没输出 JSON"时打印原文片段，防止日志被超长回复刷爆） */
    private static final int CLIP_CHARS = 40;

    private static final String INSTRUCTION = """
            [记忆整理]
            这段对话已经结束了（最后一条消息是 ${hours} 小时前），系统把它交回给你自己回顾一次。

            请以你自己的身份判断：这段交流里有哪些信息值得长期记住，记下来以后能让后续对话更懂对方。

            规则：
            1. 只记以后还用得上的事实、偏好、习惯、约定、边界与禁忌；一次性信息（临时路径、当次报错、还没定论的想法）不要记。
            2. 每条记忆独立成立，一句话一个事实。称呼与口径沿用上文 [memory] 段已有记忆的写法。
            3. 新认识与某条已有记忆冲突，或能把某条说得更准更全，用 update 带上它的 id 改写，不要另开一条相近的。
            4. 只有对方明确否定/更正了某条记忆，或该记忆已被新事实完全取代、留着会误导判断时，才用 delete。拿不准就保留。
            5. 与已有记忆重复的不要记。确实没有值得记的就输出 []，不要凑数。

            只输出 JSON 数组，不要解释、不要代码块：
            [{"mode":"add","content":"...","group":"..."},{"mode":"update","id":12,"content":"..."},{"mode":"delete","id":7,"reason":"..."}]
            mode 必填；update / delete 必须带 id；group 可省略（update 保持原分组，add 落「未分类」）。
            """;

    private final SessionMemoryJobRepository repository;

    private final ChatSessionService chatSessionService;
    private final ChatMessageService chatMessageService;
    private final ChatPromptService chatPromptService;
    private final ChatHttpHandler chatHttpHandler;
    private final MemoryService memoryService;
    private final SessionFileManager sessionFileManager;
    private final AssistantSettings assistantSettings;
    private final MemorySettings memorySettings;
    private final AISettings chatAISettings;
    private final ObjectMapper objectMapper;

    public MemoryRecordService(SessionMemoryJobRepository repository,
                               ChatSessionService chatSessionService,
                               ChatMessageService chatMessageService,
                               ChatPromptService chatPromptService,
                               ChatHttpHandler chatHttpHandler,
                               MemoryService memoryService,
                               SessionFileManager sessionFileManager,
                               AssistantSettings assistantSettings,
                               MemorySettings memorySettings,
                               @Qualifier(AISettings.CHAT) AISettings chatAISettings,
                               ObjectMapper objectMapper) {
        this.repository = repository;
        this.chatSessionService = chatSessionService;
        this.chatMessageService = chatMessageService;
        this.chatPromptService = chatPromptService;
        this.chatHttpHandler = chatHttpHandler;
        this.memoryService = memoryService;
        this.sessionFileManager = sessionFileManager;
        this.assistantSettings = assistantSettings;
        this.memorySettings = memorySettings;
        this.chatAISettings = chatAISettings;
        this.objectMapper = objectMapper;
    }

    // ======================== 续期与查询 ========================

    /**
     * 续期：每轮对话把到期时间往后推。开关关闭 / 非普通会话时什么都不做。
     * <p>只写库、不碰调度：写到哪里由 Repository 是本地实现还是 RPC 代理决定，
     * 所以 remote 实例也能正常续期，真正执行由权威库那侧轮询捡走。
     */
    public void touch(ChatSession chatSession) {
        try {
            if (chatSession == null
                    || !Boolean.TRUE.equals(memorySettings.getEnableAutoRecord())
                    || !ChatSession.TYPE_CHAT.equals(chatSession.getType())) {
                return;
            }
            LocalDateTime now = LocalDateTime.now();
            repository.upsert(new SessionMemoryJob()
                    .setSessionId(chatSession.getId())
                    .setDueTime(now.plusHours(memorySettings.getAutoRecordIdleHours()))
                    .setLastMessageTime(now));
        } catch (Exception e) {
            // 续期出问题只是这一轮不参与自动整理，不能影响正常对话
            log.warn("自动记忆续期失败 [{}]: {}", chatSession.getId(), e.getMessage());
        }
    }

    /** 到期待办（轮询用）；开关关闭时整轮不动，行原样留在库里 */
    public List<SessionMemoryJob> findDue() {
        if (!Boolean.TRUE.equals(memorySettings.getEnableAutoRecord())) {
            return List.of();
        }
        return repository.selectDue(LocalDateTime.now());
    }

    // ======================== 执行 ========================

    /**
     * 执行一次整理：删行即认领，认领不到说明已被别处拿走或期间被续期。
     *
     * @return 结果描述；未认领到时返回 null
     */
    public String execute(String sessionId) {
        SessionMemoryJob job = repository.selectBySessionId(sessionId);
        if (job == null || !repository.take(sessionId, job.getLastMessageTime())) {
            return null;
        }

        try {
            ChatSession session = chatSessionService.findById(sessionId);
            if (session == null) {
                // 待办已在 take 时删掉，这里只是收尾：会话被删后残留的待办就是这样自然收敛的
                return "跳过：会话已删除";
            }
            if (!ChatSession.TYPE_CHAT.equals(session.getType())) {
                return "跳过：非普通会话";
            }

            List<ChatMessage> history = plainHistory(chatMessageService.getConversationHistory(sessionId));

            String result = applyAnswer(callModel(buildSystemPrompt(session, history), history));
            log.info("自动记忆完成 [{}]：{}", sessionId, result);
            return result;
        } catch (Exception e) {
            // 待办已经删掉了，失败不自动重试：等用户下次进这个会话聊两句，续期会重新写一条待办
            log.warn("自动记忆整理失败 [{}]，等下次续期再排：{}", sessionId, e.getMessage(), e);
            return "失败: " + rootMessage(e);
        }
    }

    // ======================== 组装请求 ========================

    /**
     * 历史装载成可发送形态：与主对话走同一个 {@link SessionFileManager#loadSessionFile}。
     * <p>图片/音视频展开成 data URI、附件读成文本，主对话能发什么，这里就发什么，不做裁剪。
     */
    private List<ChatMessage> plainHistory(List<ChatMessage> history) {
        history.removeIf(message -> message == null || !StringUtils.hasText(message.getRole()));
        return sessionFileManager.loadSessionFile(history);
    }

    /** 与正常对话同源的系统提示词，由 ChatPromptService 统一拼装 */
    private String buildSystemPrompt(ChatSession session, List<ChatMessage> history) throws Exception {
        NoOpWebSocketSession noop = new NoOpWebSocketSession("memory-record-" + session.getId());
        ChatProvider.SystemProviderContext context = new ChatProvider.SystemProviderContext(
                session, history, noop, chatAISettings, assistantSettings);
        return chatPromptService.defaultSystemPrompt(context);
    }

    /** 发一次普通对话请求（不带工具） */
    private String callModel(String systemPrompt, List<ChatMessage> history) throws Exception {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage().system(systemPrompt));
        messages.addAll(history);
        messages.add(new ChatMessage().user(
                INSTRUCTION.replace("${hours}", String.valueOf(memorySettings.getAutoRecordIdleHours()))));

        ChatRequest request = new ChatRequest()
                .setMessages(messages)
                .loadSettings(new AISettings().copy(chatAISettings));

        AtomicReference<String> answer = new AtomicReference<>();
        chatHttpHandler.translate(
                new ChatHttpHandler.TranslateData(UUID.randomUUID().toString(), chatAISettings.getAdapterName(), request),
                new ChatHttpHandler.TranslateHandler(null, (result, lastResponse) -> {
                    if (result != null && StringUtils.hasText(result.content())) {
                        answer.set(result.content().trim());
                    }
                }),
                new ChatHttpHandler.TranslateOption().setStream(chatAISettings.getStream()).setEnableTTS(false));

        if (!StringUtils.hasText(answer.get())) {
            throw new IllegalStateException("模型没有返回内容");
        }
        return answer.get();
    }

    // ======================== 落库 ========================

    /**
     * 按 add / update / delete 落库，返回一句结果摘要。
     * <p>单条处理失败只跳过这一条：一条脏数据不该让整趟整理白跑。
     */
    private String applyAnswer(String answer) {
        String json = JsonUtils.extractJsonArray(answer);
        if (!StringUtils.hasText(json)) {
            log.info("自动记忆：模型未输出 JSON 数组，视为无新增。原文片段: {}", clip(answer));
            return "无新增";
        }
        JsonNode array;
        try {
            array = objectMapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("模型输出不是合法 JSON: " + e.getMessage());
        }
        if (array == null || !array.isArray()) {
            throw new IllegalStateException("模型输出的 JSON 不是数组");
        }

        int added = 0;
        int updated = 0;
        List<String> deleted = new ArrayList<>();

        for (JsonNode node : array) {
            try {
                String mode = node.path("mode").asText("add").trim().toLowerCase();
                String content = node.path("content").asText("").trim();
                String group = node.path("group").asText("").trim();
                Integer id = node.hasNonNull("id") ? node.get("id").asInt() : null;

                if ("delete".equals(mode)) {
                    if (id == null) {
                        continue;
                    }
                    MemoryRecord existing = memoryService.getMemoryById(id);
                    if (existing == null) {
                        continue;
                    }
                    memoryService.deleteMemory(id);
                    // 删除不可逆，日志里留全文，事后要翻能看见到底删了什么
                    deleted.add(id + "「" + existing.getContent() + "」");
                    continue;
                }

                if (!StringUtils.hasText(content)) {
                    continue;
                }
                // 只有 id 确实指向已有记忆才改写；id 缺失或查不到就按新增处理，不能瞎改
                if ("update".equals(mode) && id != null && memoryService.getMemoryById(id) != null) {
                    memoryService.addOrUpdateMemory(id, content, group, "update");
                    updated++;
                } else {
                    memoryService.addOrUpdateMemory(null, content, group, "add");
                    added++;
                }
            } catch (Exception e) {
                log.warn("自动记忆：单条处理失败，跳过: {}", e.getMessage());
            }
        }

        StringBuilder result = new StringBuilder()
                .append("新增 ").append(added).append(" 条，修正 ").append(updated).append(" 条，删除 ").append(deleted.size()).append(" 条");
        if (!deleted.isEmpty()) {
            result.append("；删除明细：").append(String.join("、", deleted));
        }
        return result.toString();
    }

    private String clip(String text) {
        if (text == null) {
            return "";
        }
        String single = text.replaceAll("\\s+", " ").trim();
        return single.length() > CLIP_CHARS ? single.substring(0, CLIP_CHARS) + "…" : single;
    }

    private String rootMessage(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause.getMessage();
        return StringUtils.hasText(message) ? message : cause.toString();
    }
}
