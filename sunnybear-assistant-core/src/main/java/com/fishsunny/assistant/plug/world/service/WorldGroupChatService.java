package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观群聊核心服务 —— 调度器选角 + 上下文渲染（角色名：内容，含私聊过滤）+ 构建每轮角色 ChatProvider + 轮次循环
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.plug.world.constant.WorldControlSign;
import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WorldGroupChatService {

    /** 旁白内置角色名（narration_enable 时作为调度器候选之一） */
    @Value("${plug.world.processor.narrator-name:旁白}")
    @Setter
    public String narratorName;

    /** 调度器最大尝试次数（初始 1 次 + 失败重试），仍失败则结束本轮 */
    @Value("${plug.world.processor.max-scheduler-attempts:2}")
    @Setter
    private int maxSchedulerAttempts;

    /** 调度器保留的上下文轮数：太短会丢失指代（他/她等），影响选角判断 */
    @Value("${plug.world.processor.scheduler-context-turns:2}")
    @Setter
    private int schedulerContextTurns;

    /** 私聊标签匹配：兼容属性顺序不定、未闭合（缺 </private> 时吃到结尾） */
    private static final Pattern PRIVATE_TAG_PATTERN = Pattern.compile(
            "(?is)<private\\b([^>]*)>(.*?)(?:</private>|\\z)");

    /** 私聊标签内的 from/to 属性提取（顺序不定、单双引号均可） */
    private static final Pattern PRIVATE_ATTR_PATTERN = Pattern.compile(
            "(?i)\\b(from|to)\\s*[:=]\\s*[\"']([^\"']*)[\"']");

    /** 发言权移交标签：<switch to="角色名"/>，命中则跳过调度器直接把话头交给目标角色 */
    private static final Pattern SWITCH_TAG_PATTERN = Pattern.compile(
            "(?is)<switch\\s+to\\s*[:=]\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s\"'>]+))");

    private final ChatSessionService chatSessionService;
    private final WorldInfoService worldInfoService;
    private final WorldCharacterService worldCharacterService;
    private final WorldKnowledgeService worldKnowledgeService;
    private final ChatMessageService chatMessageService;
    private final ChatProcessor chatProcessor;
    private final ChatHttpHandler chatHttpHandler;
    private final UserSettings userSettings;
    private final ObjectMapper objectMapper;

    public WorldGroupChatService(ChatSessionService chatSessionService,
                                 WorldInfoService worldInfoService,
                                 WorldCharacterService worldCharacterService,
                                 WorldKnowledgeService worldKnowledgeService,
                                 ChatMessageService chatMessageService,
                                 ChatProcessor chatProcessor,
                                 ChatHttpHandler chatHttpHandler,
                                 UserSettings userSettings,
                                 ObjectMapper objectMapper) {
        this.chatSessionService = chatSessionService;
        this.worldInfoService = worldInfoService;
        this.worldCharacterService = worldCharacterService;
        this.worldKnowledgeService = worldKnowledgeService;
        this.chatMessageService = chatMessageService;
        this.chatProcessor = chatProcessor;
        this.chatHttpHandler = chatHttpHandler;
        this.userSettings = userSettings;
        this.objectMapper = objectMapper;
    }

    /**
     * 通过会话 ID 找到绑定的世界观。
     * <p>一次性注册已取代"先 create、后 HTTP bind"的延时绑定：新会话的 extension 在 create 时即随会话落库，
     * 旧绑定也已由启动迁移写入 extension，因此不需要再轮询等待。
     */
    public WorldInfo resolveWorld(String sessionId) {
        ChatSession session = chatSessionService.findById(sessionId);
        String worldId = WorldSessionBindings.resolveWorldId(session);
        if (worldId == null) {
            throw new RuntimeException("会话未绑定世界观，请从世界页发起对话");
        }
        WorldInfo world = worldInfoService.findById(worldId);
        if (world == null) {
            throw new RuntimeException("世界观不存在: " + worldId);
        }
        return world;
    }

    /**
     * 群聊轮次循环：调度器选角 → 夺舍校验 → 被选角色生成，最多执行 maxRounds 轮。
     * 每轮调用一次 chatToAi，落盘一条 role=assistant（name=角色名）消息。
     */
    public void runGroupRounds(ChatSession chatSession, WebSocketSession session) throws Exception {
        WorldInfo world = resolveWorld(chatSession.getId());

        List<WorldCharacter> characters = worldCharacterService.findByWorldId(world.getId());
        Map<String, WorldCharacter> byName = characters.stream()
                .collect(Collectors.toMap(
                        WorldCharacter::getName,
                        Function.identity(),
                        (a, b) -> a)
                );
        if (characters.isEmpty()) {
            log.warn("世界观 [{}] 没有任何可发言的角色，跳过本轮", world.getId());
            return;
        }

        int maxRounds = world.getMaxRounds() == null ? 5 : world.getMaxRounds();
        String possessName = world.getPossessName();

        for (int round = 0; round < maxRounds; round++) {
            List<ChatMessage> history = chatMessageService.getConversationHistory(chatSession.getId());
            // 调度器保留最近 N 轮（用户消息回合）上下文，太短会丢失指代
            String contextText = renderContext(world, lastTurnsContext(history), null);

            // 发言权移交：上一条消息若带 <switch to:"X">，跳过调度器直接把话头交给 X
            String switchTo = extractSwitchTarget(lastMessageText(history));
            String chosen;
            if (StringUtils.hasText(switchTo) && isValidSwitchTarget(world, characters, switchTo)) {
                log.info("检测到发言权移交标签，跳过调度器 -> [{}]", switchTo);
                chosen = switchTo;
            } else {
                if (StringUtils.hasText(switchTo)) {
                    log.warn("发言权移交目标 [{}] 不在候选角色中，回退调度器", switchTo);
                }
                chosen = selectSpeaker(world, contextText, characters, lastSpeaker(history));
            }
            if (!StringUtils.hasText(chosen)) {
                log.warn("调度器未给出有效选角，轮次结束 [round={}]", round);
                break;
            }
            // 用户夺舍语义：调度器选中夺舍角色时不再生成，直接把回合交还用户
            if (StringUtils.hasText(possessName) && possessName.equals(chosen)) {
                log.info("调度器选中夺舍角色 [{}]，停止本轮生成", chosen);
                // 通知前端回合已交还玩家（群聊页据此提示"轮到你了"）
                session.sendMessage(new TextMessage(WorldControlSign.WORLD_POSSESS + chatSession.getId() + "|" + chosen));
                break;
            }

            ChatProvider provider;
            if (narratorName.equals(chosen)) {
                provider = buildNarratorProvider(world);
            } else {
                WorldCharacter character = byName.get(chosen);
                if (character == null) {
                    log.warn("调度器选中了不存在的角色 [{}]，轮次结束", chosen);
                    break;
                }
                provider = buildCharacterProvider(world, character, characters);
            }
            // 通知前端本轮发言者，群聊页据此开启新气泡并显示角色名
            session.sendMessage(new TextMessage(WorldControlSign.WORLD_ROUND + chatSession.getId() + "|" + chosen));
            // 被选角色看完整会话历史（sessionMessageProvider 折叠成单条 user 消息）
            chatProcessor.chatToAi(history, chatSession, session, provider);
        }
    }

    /**
     * 取最近 turns 轮（以用户消息为界）的历史：从倒数第 turns 条用户消息起，到末尾。
     * 供调度器读取足够的对话上下文，避免只看当前回合而丢失指代。
     */
    private List<ChatMessage> lastTurnsContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        int userCount = 0;
        int startIdx = 0;
        for (int i = history.size() - 1; i >= 0; i--) {
            if (ChatMessage.ROLE_USER.equals(history.get(i).getRole())) {
                userCount++;
                if (userCount == schedulerContextTurns) {
                    startIdx = i;
                    break;
                }
            }
        }
        return history.subList(startIdx, history.size());
    }

    /**
     * 调度器 AI 调用：输入当前回合上下文 + 候选列表，JSON 输出选中角色名。
     * 最多尝试 {@link #maxSchedulerAttempts} 次，非法输出时携带上次原始结果反馈重试；
     * 仍失败则返回 null（由调用方结束本轮）。
     * <p>
     * 防连续重复机制：当首轮选中的角色与上一轮发言者相同时，会再调度一次复核——
     * 两次一致则尊重调度器仍选他；两次不一致则以第二次为准；复核失败则保留首轮结果。
     *
     * @param world            世界观
     * @param contextText      当前对话上下文
     * @param candidatesCharacters 候选角色列表
     * @param previousSpeaker  上一轮发言者名（可能为 null，表示无参考）
     * @return 选中角色名（含旁白）；无法决策时返回 null
     */
    public String selectSpeaker(WorldInfo world, String contextText, List<WorldCharacter> candidatesCharacters, String previousSpeaker) throws Exception {
        if (CollectionUtils.isEmpty(candidatesCharacters)) {
            return null;
        }
        boolean narrationEnabled = !Boolean.FALSE.equals(world.getNarrationEnable());
        String possessName = world.getPossessName();

        // 校验用候选名列表（旁白启用时纳入，修复"旁白永远选不出来"）
        List<String> candidates = new ArrayList<>();
        for (WorldCharacter character : candidatesCharacters) {
            if (StringUtils.hasText(character.getName())) {
                candidates.add(character.getName());
            }
        }
        if (narrationEnabled) {
            candidates.add(narratorName);
        }

        // 展示用候选描述：角色名：简介，夺舍角色标注真人玩家
        List<String> candidatesWithDesc = new ArrayList<>();
        for (WorldCharacter character : candidatesCharacters) {
            if (!StringUtils.hasText(character.getName())) {
                continue;
            }
            String line = character.getName() + ": " + character.getIntro();
            if (StringUtils.hasText(possessName) && possessName.equals(character.getName())) {
                line += "（由真人玩家扮演）";
            }
            candidatesWithDesc.add(line);
        }
        if (narrationEnabled) {
            candidatesWithDesc.add(narratorName + "：负责场景描述、氛围渲染与转场");
        }

        String candidateDesc = String.join("\n", candidatesWithDesc);
        AISettings schedulerSettings = resolveSchedulerSettings(world);
        String system = buildSchedulerSystemPrompt(candidateDesc, narrationEnabled, possessName);

        // 首轮调度决策
        String first = runSchedulerDecision(system, schedulerSettings, candidates, contextText);
        if (!StringUtils.hasText(first)) {
            log.warn("调度器未给出有效选角");
            return null;
        }

        // 防连续重复：首轮选角与上一轮发言者相同 → 再调度一次复核
        if (StringUtils.hasText(previousSpeaker) && previousSpeaker.equals(first)) {
            String second = runSchedulerDecision(system, schedulerSettings, candidates, contextText);
            if (StringUtils.hasText(second)) {
                if (second.equals(first)) {
                    // 两次一致：尊重调度器，仍然选他
                    log.info("调度器复核：两次选角一致 [{}]，尊重结果", first);
                } else {
                    // 两次不一致：以第二次为准
                    log.info("调度器复核：首轮 [{}] 与上一轮相同，二轮改为 [{}]", first, second);
                    return second;
                }
            } else {
                // 复核失败：保留首轮结果，避免无故结束本轮
                log.warn("调度器复核失败，保留首轮选角 [{}]", first);
            }
        }
        return first;
    }

    /**
     * 执行一次调度器选角决策（内部含失败重试，与 {@link #maxSchedulerAttempts} 对齐）。
     *
     * @return 合法候选名；全部尝试失败时返回 null
     */
    private String runSchedulerDecision(String system, AISettings schedulerSettings, List<String> candidates, String contextText) throws Exception {
        String lastRaw = null;
        for (int attempt = 0; attempt < maxSchedulerAttempts; attempt++) {
            String userContent = "对话内容：\n" + contextText;
            if (attempt > 0) {
                userContent += "\n\n【上一次决策无效】你返回了「" + (lastRaw == null ? "空内容" : lastRaw) + "」，"
                        + "它不在候选名单中或不是合法的 JSON。请重新决策：name 必须严格等于候选名单中的某一个，只输出 JSON 对象。";
            }
            ChatRequest request = new ChatRequest()
                    .loadSettings(new AISettings().copy(schedulerSettings).json())
                    .setMessages(List.of(
                            new ChatMessage().system(system),
                            new ChatMessage().user(userContent)
                    ));

            AtomicReference<String> chosen = new AtomicReference<>();
            AtomicReference<String> rawHolder = new AtomicReference<>();
            chatHttpHandler.translate(UUID.randomUUID().toString(), schedulerSettings.getAdapterName(),
                    request, false, null,
                    (result, lastRes) -> {
                        rawHolder.set(result.content());
                        chosen.set(parseName(result.content()));
                    });

            lastRaw = rawHolder.get();
            String name = chosen.get();
            if (StringUtils.hasText(name) && candidates.contains(name)) {
                return name;
            }
            log.warn("调度器第 {} 次决策无效 [raw={}]", attempt + 1, lastRaw);
        }
        log.warn("调度器 {} 次尝试均无效", maxSchedulerAttempts);
        return null;
    }

    /**
     * 调度器系统提示词：选角原则（剧情关联度优先、旁白克制）+ 候选名单 + 夺舍/旁白特殊说明 + few-shot 示例。
     */
    private String buildSchedulerSystemPrompt(String candidateDesc, boolean narrationEnabled, String possessName) {
        String special = "无";
        if (StringUtils.hasText(possessName) || narrationEnabled) {
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(possessName)) {
                sb.append("「").append(possessName).append("」由真人玩家扮演。\n");
            }
            if (narrationEnabled) {
                sb.append("「").append(narratorName).append("」是本群聊的合法候选，负责场景描述、氛围渲染与转场。\n");
            }
            special = sb.toString().trim();
        }
        return """
                你是群聊场景的【调度员】。你的唯一职责是决定：在当前剧情节点，下一位该谁发言。你不写台词、不参与剧情，只做选角决策。

                你的目标是让群聊像一部好看的剧：每一次出场都顺理成章，每一句话都推动剧情或塑造人物，节奏张弛有度。

                ## 输入
                - 【当前对话】：按时间正序，格式「角色名：发言」，最后一条是剧情的最新节点。
                - 【候选名单】：每个角色一行，格式「角色名：简介」。%s

                ## 选角原则（严格按优先级）
                1. 【剧情关联度·最高优先】只选此刻"在场景里、与当前事件/冲突/对话有直接关联、真正有话说或该有所行动"的角色。坚决避免选出与当下场景无关、没有理由在场的人。
                2. 【直接回应】若最新一句明确点名或直指某角色（提问、对峙、打招呼、喊名字），让被针对的角色接话；除非刻意的沉默更能制造张力。
                3. 【推动剧情】否则，选与当前冲突/情绪绑得最深、最可能让事态升级或揭示新信息的角色。
                4. 【节奏】非必要不让同一人连续开口；当几个角色都合理在场时，优先给较久没说话的那个镜头。
                5. 【${narrator_name}】仅在场景切换、时间流逝、需要交代环境/氛围、或对话僵住需要破局时，才选${narrator_name}。不要频繁用${narrator_name}打断对话节奏。
                
                ## 特殊说明
                %s

                ## 关于夺舍角色
                若特殊说明提到某角色由真人玩家扮演：当剧情最该轮到他接话、或需要玩家做出选择时，选他（选中后回合会交还给玩家）。其余情况正常对待。

                ## 关于消息中的特殊标签（你只用于理解，绝不输出）
                对话里可能带有两类特殊标记，它们不是普通台词：
                - `<switch to:"角色名">`：上一位发言者主动把发言权移交给目标角色，是控制信号而非台词。最近一条消息带此标签时，系统会直接让目标角色接话（通常已跳过你的调度）；更早出现时，把它理解成"此处发言权已转移"即可。
                - `<private from="发送者" to="接收者">内容</private>`：这是一条私聊，只有发送者与指定接收者知晓，其余角色看不到也不知道。你作为调度员是全知的，能看到全部私聊，但普通角色并不知道。选角时请尊重信息不对称：不要让某个角色去回应他不知道的私聊内容，收到私聊的角色更可能对此有反应。
                你只输出选角 JSON，绝不输出任何标签。

                ## 输出
                只输出一个 JSON 对象，不得有其它任何文字：
                {"reason": "一句话理由", "name": "角色名"}
                - reason 必须写在 name 之前（先论证理由，再定名字，最后才下结论）。
                - name 必须严格等于候选名单中的某一个（${narrator_name}若启用也是合法选项）。
                - reason 是内部决策依据，一句话即可，绝不能出现在任何角色发言里。

                ## 示例
                示例1：
                对话：…「林月：你们到底是谁派来的？老实交代。」
                候选：林月 / 陈默 / 阿澈 / ${narrator_name}
                输出：{"reason": "林月的质问直指陈默，由他正面回应最合理", "name": "陈默"}

                示例2：
                对话：…「陈默：林月，那包裹里的东西到底和你有没有关系？」「林月：……和我没关系。」（两人已来回多轮）
                候选：林月 / 陈默 / 阿澈 / ${narrator_name}
                输出：{"reason": "两人对峙陷入僵局，让第三方阿澈打破平衡、推动冲突", "name": "阿澈"}

                示例3：
                对话：…「陈默：那就这么定了，明天午夜，码头见。」
                候选：林月 / 陈默 / 阿澈 / ${narrator_name}
                输出：{"reason": "约定达成、场景即将切换，交给${narrator_name}转场并渲染夜色氛围", "name": "${narrator_name}"}
                """.formatted(candidateDesc, special);
    }

    /**
     * 从调度器返回文本中解析 JSON 的 name 字段，容错 ```json 代码块包裹。
     */
    private String parseName(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String json = raw.trim()
                .replaceAll("^```(json)?\\s*", "")
                .replaceAll("```\\s*$", "")
                .trim();
        try {
            Map<String, String> parsed = objectMapper.readValue(json, new TypeReference<>() {
            });
            String reason = parsed.get("reason");
            if (StringUtils.hasText(reason)) {
                log.info("调度器理由: {}", reason);
            }
            String name = parsed.get("name");
            if (StringUtils.hasText(name)) {
                return name.trim();
            }
        } catch (Exception e) {
            log.warn("解析调度器 JSON 失败: {}", e.getMessage());
        }
        return null;
    }

    /**
     * 构建某个群组角色的 ChatProvider：
     * 系统提示 = 世界预设 + 角色设定 + 角色知晓的知识（+ 私聊频道用法）；
     * 上下文折叠成单条 "角色名：内容" 的 user 消息；禁用全部工具；模型用角色自己的 ai_settings。
     */
    public ChatProvider buildCharacterProvider(WorldInfo world, WorldCharacter character, List<WorldCharacter> characters) {
        List<String> knownKnowledge = knownKnowledge(world, character);
        String systemPrompt = buildSystemPrompt(world, character, knownKnowledge, characters);
        AISettings charAi = parseCharacterAiSettings(character.getAiSettings());
        String listener = character.getName();
        return new ChatProvider()
                .setSystemProvider(ctx -> systemPrompt)
                .setSessionMessageProvider(messages -> collapseContext(world, messages, listener))
                .setToolProvider(ctx -> List.of())
                .setSettingsSupplier(() -> new ChatProvider.Settings(charAi, charAi, new AssistantSettings().setAssistantName(listener)))
                .setEnableSlashCommand(() -> false)
                .setEnableSwitchPro(() -> false)
                .setBeforeSaveAssistantProvider((chatMessage -> {
                    String text = chatMessage.resolveText();
                    if (text.startsWith(listener + ":") || text.startsWith(listener + "：")) {
                        chatMessage.text(text.substring(listener.length() + 1).trim());
                    }
                    return chatMessage;
                }));
    }

    /**
     * 构建内置旁白的 ChatProvider（全知第三人称，模型用全局 chat 配置）。
     * 注入：世界预设 + 世界描述 + 全部角色设定 + 全部世界知识（旁白全知，不受知晓角色限制）。
     */
    public ChatProvider buildNarratorProvider(WorldInfo world) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                你是${narrator_name}，本群聊的旁白，以全知第三人称视角叙述场景、环境、角色的动作、神态与氛围。
                你不扮演任何角色，不代替角色说话，只做客观、克制的场景叙述。
                """);
        if (StringUtils.hasText(world.getDescription())) {
            sb.append("## 世界背景\n").append(world.getDescription()).append("\n\n");
        }
        // 注入全部角色设定，保证旁白叙述不违背角色人设
        List<WorldCharacter> characters = worldCharacterService.findByWorldId(world.getId());
        if (!characters.isEmpty()) {
            sb.append("\n## 角色设定\n");
            for (WorldCharacter character : characters) {
                sb.append("【").append(character.getName()).append("】");
                if (StringUtils.hasText(character.getSetting())) {
                    sb.append(character.getSetting().trim()).append("\n");
                }
                sb.append("\n");
            }
        }
        List<WorldKnowledge> knowledges = worldKnowledgeService.findByWorldId(world.getId());
        if (!knowledges.isEmpty()) {
            sb.append("\n## 世界知识\n");
            for (WorldKnowledge knowledge : knowledges) {
                sb.append("【").append(knowledge.getTitle()).append("】");
                if (StringUtils.hasText(knowledge.getContent())) {
                    sb.append(" ").append(knowledge.getContent().trim()).append("\n");
                }
                sb.append("\n");
            }
        }
        String systemPrompt = sb.toString();
        return new ChatProvider()
                .setSystemProvider(ctx -> systemPrompt)
                .setSessionMessageProvider(messages -> collapseContext(world, messages, null))
                .setToolProvider(ctx -> List.of())
                .setSettingsSupplier(() -> new ChatProvider.Settings(null, null, new AssistantSettings().setAssistantName(narratorName)))
                .setEnableSlashCommand(() -> false)
                .setEnableSwitchPro(() -> false)
                .setBeforeSaveAssistantProvider((chatMessage -> {
                    String text = chatMessage.resolveText();
                    if (text.startsWith(narratorName + ":") || text.startsWith(narratorName + "：")) {
                        chatMessage.text(text.substring(narratorName.length() + 1).trim());
                    }
                    return chatMessage;
                }));
    }

    // ==================== 内部方法 ====================

    /**
     * 将完整历史折叠成单条 user 消息："角色名：内容" 逐行拼接。
     * 保留最近一条真实 user 消息的 id 作为折叠消息 id，让落盘的 assistant 消息 parentId 指向真实用户消息。
     *
     * @param listener 当前要发言的角色名；null 表示全知视角（调度器/旁白，私聊内容不剔除）
     */
    private List<ChatMessage> collapseContext(WorldInfo world, List<ChatMessage> history, String listener) {
        String lastUserId = null;
        for (ChatMessage message : history) {
            if (ChatMessage.ROLE_USER.equals(message.getRole())) {
                lastUserId = message.getId();
            }
        }
        String rendered = renderContext(world, history, listener);
        ChatMessage collapsed = new ChatMessage().user(rendered);
        collapsed.setId(lastUserId);
        return List.of(collapsed);
    }

    /**
     * 按 "角色名：内容" 渲染上下文；私聊启用时对监听者按标签粒度过滤私聊。
     * 每条消息 = 公开文本 + 若干 {@code <private>} 标签：
     * - 公开文本总是保留；
     * - 私聊标签仅当监听者在其 from/to 中（或监听者全知）时原样保留，不加工成提示文本；
     * - 其余私聊标签剔除。
     */
    public String renderContext(WorldInfo world, List<ChatMessage> history, String listener) {
        if (CollectionUtils.isEmpty(history)) {
            return "";
        }
        // 私聊频道固定开启，始终按标签粒度过滤
        String possessName = world.getPossessName();
        StringBuilder builder = new StringBuilder();
        for (ChatMessage m : history) {
            String content = m.resolveText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            // switch 标签是发言权移交动作，谁交给谁本身就是剧情信息，原样保留进上下文
            String visible = filterPrivateContent(content, listener);
            if (!StringUtils.hasText(visible)) {
                continue;
            }
            String speaker = speakerName(m, possessName);
            builder.append(speaker).append("：").append(visible).append("\n\n");
        }
        return builder.toString();
    }

    /** 消息的发言者名：用户消息 = 夺舍角色名（否则用户名）；assistant = 其 name */
    private String speakerName(ChatMessage m, String possessName) {
        if (ChatMessage.ROLE_USER.equals(m.getRole())) {
            return StringUtils.hasText(possessName) ? possessName : userSettings.getUsername();
        }
        return StringUtils.hasText(m.getName()) ? m.getName() : "助手";
    }

    /** 最后一条消息的文本，用于检测发言权移交标签 */
    private String lastMessageText(List<ChatMessage> history) {
        if (CollectionUtils.isEmpty(history)) {
            return null;
        }
        ChatMessage last = history.getLast();
        return last == null ? null : last.resolveText();
    }

    /** 最近一条消息的发言者名（上一轮的发言者）；无则返回 null */
    private String lastSpeaker(List<ChatMessage> history) {
        if (CollectionUtils.isEmpty(history)) {
            return null;
        }
        return history.getLast().getName();
    }

    /** 提取 <switch to:"X"> 的目标角色名；无标签时返回 null */
    private String extractSwitchTarget(String content) {
        if (!StringUtils.hasText(content)) {
            return null;
        }
        Matcher matcher = SWITCH_TAG_PATTERN.matcher(content);
        if (matcher.find()) {
            String target = matcher.group(1);
            if (!StringUtils.hasText(target)) {
                target = matcher.group(2);
            }
            if (!StringUtils.hasText(target)) {
                target = matcher.group(3);
            }
            return StringUtils.hasText(target) ? target.trim() : null;
        }
        return null;
    }

    /** switch 目标是否合法：必须是世界内角色（或旁白启用时的旁白） */
    private boolean isValidSwitchTarget(WorldInfo world, List<WorldCharacter> characters, String target) {
        if (!StringUtils.hasText(target)) {
            return false;
        }
        boolean narrationEnabled = !Boolean.FALSE.equals(world.getNarrationEnable());
        if (narrationEnabled && narratorName.equals(target)) {
            return true;
        }
        return characters.stream()
                .map(WorldCharacter::getName)
                .filter(StringUtils::hasText)
                .anyMatch(target::equals);
    }

    /**
     * 按标签粒度过滤私聊内容：在原始字符串上切片，避免 HTML 解析重序列化破坏公开正文。
     * listener 为 null（调度器/旁白全知）时保留全部私聊；否则仅保留监听者参与（from/to）的私聊标签。
     * 未闭合的私聊标签一律按私聊处理，绝不静默变成公开内容。
     */
    private String filterPrivateContent(String content, String listener) {
        Matcher matcher = PRIVATE_TAG_PATTERN.matcher(content);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            // 标签之前的公开文本
            out.append(content, last, matcher.start());
            String attrs = matcher.group(1);
            if (listener == null || privateVisibleTo(attrs, listener)) {
                // 原样保留原始 <private> 标签：让角色在上下文中直接看到正确写法、学会怎么用，
                // 而不是只看到加工后的提示文本（上下文一长模型会忘了标签格式）
                out.append(matcher.group());
            }
            last = matcher.end();
        }
        out.append(content, last, content.length());
        return out.toString().trim();
    }

    /** 从 <private ...> 开始标签的属性文本解析 from/to，判断监听者是否可见 */
    private boolean privateVisibleTo(String attrs, String listener) {
        Matcher attrMatcher = PRIVATE_ATTR_PATTERN.matcher(attrs);
        String from = "";
        String to = "";
        while (attrMatcher.find()) {
            String name = attrMatcher.group(1);
            String value = attrMatcher.group(2).trim();
            if ("from".equals(name)) {
                from = value;
            } else if ("to".equals(name)) {
                to = value;
            }
        }
        if (listener.equals(from)) {
            return true;
        }
        return Arrays.stream(to.split("[,\\s，、;；]+"))
                .filter(StringUtils::hasText)
                .anyMatch(listener::equals);
    }

    /** 角色系统提示词：世界描述 + 世界预设 + 角色设定 + 角色知晓的知识（+ 私聊频道用法） */
    private String buildSystemPrompt(WorldInfo world, WorldCharacter character, List<String> knownKnowledge, List<WorldCharacter> characters) {
        StringBuilder systemPrompt = new StringBuilder();
        if (StringUtils.hasText(world.getPreset())) {
            systemPrompt.append(world.getPreset()).append("\n\n");
        }
        if (StringUtils.hasText(world.getDescription())) {
            systemPrompt.append("## 世界背景\n").append(world.getDescription()).append("\n\n");
        }
        if (StringUtils.hasText(character.getSetting())) {
            systemPrompt.append("## 角色设定\n").append(character.getSetting()).append("\n\n");
        }
        if (!knownKnowledge.isEmpty()) {
            systemPrompt.append("## 世界知识\n");
            for (String knowledge : knownKnowledge) {
                systemPrompt.append("- ").append(knowledge).append("\n");
            }
            systemPrompt.append("\n\n");
        }
        if (!CollectionUtils.isEmpty(characters)) {
            systemPrompt.append("## 角色列表\n");
            for (WorldCharacter c : characters) {
                if (c.getName().equals(character.getName())) {
                    continue;
                }
                systemPrompt.append("- ").append(c.getName()).append("：").append(c.getIntro()).append("\n");
            }
            systemPrompt.append("\n\n");
        }
        return systemPrompt.toString()
                .replace("${character_name}", character.getName())
                .replace("${character_intro}", character.getIntro())
                .replace("${character_setting}", character.getSetting())
                .replace("${narrator_name}", narratorName);
    }

    /** 角色知晓的知识（title：content），来自 world_knowledge + world_knowledge_character */
    private List<String> knownKnowledge(WorldInfo world, WorldCharacter character) {
        List<WorldKnowledge> knowledgeList = worldKnowledgeService.findByWorldId(world.getId());
        List<String> result = new ArrayList<>();
        if (knowledgeList == null) {
            return result;
        }
        for (WorldKnowledge knowledge : knowledgeList) {
            if (CollectionUtils.isEmpty(knowledge.getCharacterIds()) || !knowledge.getCharacterIds().contains(character.getId())) {
                continue;
            }
            StringBuilder knowledgeSection = new StringBuilder();
            if (StringUtils.hasText(knowledge.getTitle())) {
                knowledgeSection.append(knowledge.getTitle());
            }
            if (StringUtils.hasText(knowledge.getContent())) {
                if (!knowledgeSection.isEmpty()) {
                    knowledgeSection.append("：");
                }
                knowledgeSection.append(knowledge.getContent());
            }
            if (!knowledgeSection.isEmpty()) {
                result.add(knowledgeSection.toString());
            }
        }
        return result;
    }

    /** 解析角色 ai_settings JSON，缺省回退全局 chat 配置 */
    private AISettings parseCharacterAiSettings(String json) {
        try {
            return objectMapper.readValue(json, AISettings.class);
        } catch (Exception e) {
            log.warn("解析角色 ai_settings 失败: {}", e.getMessage());
            throw new RuntimeException("解析角色 ai_settings 失败:" + e.getMessage());
        }
    }

    /** 解析世界观调度器 AI 配置 JSON（adapterName/model），缺省回退 cub */
    private AISettings resolveSchedulerSettings(WorldInfo world) {
        String json = world.getSchedulerAiSettings();
        try {
            return objectMapper.readValue(json, AISettings.class);
        } catch (Exception e) {
            log.warn("解析世界观调度器配置失败: {}", e.getMessage());
            throw new RuntimeException("解析世界观调度器配置失败:" + e.getMessage());
        }
    }
}
