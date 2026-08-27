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
import com.fishsunny.assistant.plug.world.constant.WorldControlSign;
import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WorldGroupChatService {

    /** 旁白内置角色名（narration_enable 时作为调度器候选之一） */
    public static final String NARRATOR_NAME = "旁白";

    /** 调度器最大尝试次数（初始 1 次 + 失败重试），仍失败则结束本轮 */
    private static final int MAX_SCHEDULER_ATTEMPTS = 3;

    /** 私聊标签格式：<private from="发送者" to="接收者,逗号分隔">内容</private> */
    private static final String PRIVATE_TAG_SELECTOR = "private";

    /** 私聊频道使用说明（注入各角色系统提示词） */
    private static final String PRIVATE_USAGE = """
            ## 私聊频道使用说明
            你可以发起私聊，格式为：<private from="你的名字" to="接收者,逗号分隔">内容</private>
            只有 from 与 to 中出现的角色能看到这条私聊内容，其他角色不会得知。公共发言不要使用该标签。
            """;

    private final WorldSessionMappingService mappingService;
    private final WorldInfoService worldInfoService;
    private final WorldCharacterService worldCharacterService;
    private final WorldKnowledgeService worldKnowledgeService;
    private final ChatMessageService chatMessageService;
    private final ChatProcessor chatProcessor;
    private final ChatHttpHandler chatHttpHandler;
    private final UserSettings userSettings;
    private final AISettings chatAISettings;
    private final AISettings chatProAISettings;
    private final AISettings cubAISettings;
    private final ObjectMapper objectMapper;

    public WorldGroupChatService(WorldSessionMappingService mappingService,
                                 WorldInfoService worldInfoService,
                                 WorldCharacterService worldCharacterService,
                                 WorldKnowledgeService worldKnowledgeService,
                                 ChatMessageService chatMessageService,
                                 ChatProcessor chatProcessor,
                                 ChatHttpHandler chatHttpHandler,
                                 UserSettings userSettings,
                                 @Qualifier(AISettings.CHAT) AISettings chatAISettings,
                                 @Qualifier(AISettings.CHAT_PRO) AISettings chatProAISettings,
                                 @Qualifier(AISettings.CUB) AISettings cubAISettings,
                                 ObjectMapper objectMapper) {
        this.mappingService = mappingService;
        this.worldInfoService = worldInfoService;
        this.worldCharacterService = worldCharacterService;
        this.worldKnowledgeService = worldKnowledgeService;
        this.chatMessageService = chatMessageService;
        this.chatProcessor = chatProcessor;
        this.chatHttpHandler = chatHttpHandler;
        this.userSettings = userSettings;
        this.chatAISettings = chatAISettings;
        this.chatProAISettings = chatProAISettings;
        this.cubAISettings = cubAISettings;
        this.objectMapper = objectMapper;
    }

    /**
     * 通过会话 ID 找到绑定的世界观，MODE_CREATE 时前端 HTTP bind 请求可能还在路上，轮询等待。
     */
    public WorldInfo resolveWorld(String sessionId) {
        WorldSessionMapping mapping = null;
        for (int i = 0; i < 50; i++) {
            mapping = mappingService.findBySessionId(sessionId);
            if (mapping != null) break;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待世界观绑定时被中断");
            }
        }
        if (mapping == null) {
            throw new RuntimeException("会话未绑定世界观");
        }
        WorldInfo world = worldInfoService.findById(mapping.getWorldId());
        if (world == null) {
            throw new RuntimeException("世界观不存在: " + mapping.getWorldId());
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
            // 调度器只看当前回合（最近一条用户消息往后），轻量省 token
            String contextText = renderContext(world, currentTurnContext(history), null);

            String chosen = selectSpeaker(world, contextText, characters);
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
            if (NARRATOR_NAME.equals(chosen)) {
                provider = buildNarratorProvider(world);
            } else {
                WorldCharacter character = byName.get(chosen);
                if (character == null) {
                    log.warn("调度器选中了不存在的角色 [{}]，轮次结束", chosen);
                    break;
                }
                provider = buildCharacterProvider(world, character);
            }
            // 通知前端本轮发言者，群聊页据此开启新气泡并显示角色名
            session.sendMessage(new TextMessage(WorldControlSign.WORLD_ROUND + chatSession.getId() + "|" + chosen));
            // 被选角色看完整会话历史（sessionMessageProvider 折叠成单条 user 消息）
            chatProcessor.chatToAi(history, chatSession, session, provider);
        }
    }

    /**
     * 取当前回合上下文：最近一条用户消息及其之后的消息。
     */
    private List<ChatMessage> currentTurnContext(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) {
            return history;
        }
        int lastUserIdx = -1;
        for (int i = 0; i < history.size(); i++) {
            if (ChatMessage.ROLE_USER.equals(history.get(i).getRole())) {
                lastUserIdx = i;
            }
        }
        if (lastUserIdx < 0) {
            return history;
        }
        return history.subList(lastUserIdx, history.size());
    }

    /**
     * 调度器 AI 调用：输入当前回合上下文 + 候选列表，JSON 输出选中角色名。
     * 最多尝试 {@link #MAX_SCHEDULER_ATTEMPTS} 次，非法输出时携带上次原始结果反馈重试；
     * 仍失败则返回 null（由调用方结束本轮）。
     *
     * @return 选中角色名（含旁白）；无法决策时返回 null
     */
    public String selectSpeaker(WorldInfo world, String contextText, List<WorldCharacter> candidatesCharacters) throws Exception {
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
            candidates.add(NARRATOR_NAME);
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
            candidatesWithDesc.add(NARRATOR_NAME + "：负责场景描述、氛围渲染与转场");
        }

        String candidateDesc = String.join("\n", candidatesWithDesc);
        AISettings schedulerSettings = resolveSchedulerSettings(world);
        String system = buildSchedulerSystemPrompt(candidateDesc, narrationEnabled, possessName);

        String lastRaw = null;
        for (int attempt = 0; attempt < MAX_SCHEDULER_ATTEMPTS; attempt++) {
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
        log.warn("调度器 {} 次尝试均无效，本轮结束", MAX_SCHEDULER_ATTEMPTS);
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
                sb.append("「").append(NARRATOR_NAME).append("」是本群聊的合法候选，负责场景描述、氛围渲染与转场。\n");
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
                5. 【旁白】仅在场景切换、时间流逝、需要交代环境/氛围、或对话僵住需要破局时，才选旁白。不要频繁用旁白打断对话节奏。
                
                ## 特殊说明
                %s

                ## 关于夺舍角色
                若特殊说明提到某角色由真人玩家扮演：当剧情最该轮到他接话、或需要玩家做出选择时，选他（选中后回合会交还给玩家）。其余情况正常对待。

                ## 输出
                只输出一个 JSON 对象，不得有其它任何文字：
                {"reason": "一句话理由", "name": "角色名"}
                - reason 必须写在 name 之前（先论证理由，再定名字，最后才下结论）。
                - name 必须严格等于候选名单中的某一个（旁白若启用也是合法选项）。
                - reason 是内部决策依据，一句话即可，绝不能出现在任何角色发言里。

                ## 示例
                示例1：
                对话：…「林月：你们到底是谁派来的？老实交代。」
                候选：林月 / 陈默 / 阿澈 / 旁白
                输出：{"reason": "林月的质问直指陈默，由他正面回应最合理", "name": "陈默"}

                示例2：
                对话：…「陈默：林月，那包裹里的东西到底和你有没有关系？」「林月：……和我没关系。」（两人已来回多轮）
                候选：林月 / 陈默 / 阿澈 / 旁白
                输出：{"reason": "两人对峙陷入僵局，让第三方阿澈打破平衡、推动冲突", "name": "阿澈"}

                示例3：
                对话：…「陈默：那就这么定了，明天午夜，码头见。」
                候选：林月 / 陈默 / 阿澈 / 旁白
                输出：{"reason": "约定达成、场景即将切换，交给旁白转场并渲染夜色氛围", "name": "旁白"}
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
    public ChatProvider buildCharacterProvider(WorldInfo world, WorldCharacter character) {
        List<String> knownKnowledge = knownKnowledge(world, character);
        String systemPrompt = buildSystemPrompt(world, character, knownKnowledge);
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
     */
    public ChatProvider buildNarratorProvider(WorldInfo world) {
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(world.getPreset())) {
            sb.append(world.getPreset()).append("\n\n");
        }
        sb.append("你是本群聊的旁白，以全知第三人称视角叙述场景、环境、角色的动作、神态与氛围。")
                .append("你不扮演任何角色，不代替角色说话，只做客观、克制的场景叙述。\n");
        if (Boolean.TRUE.equals(world.getPrivateChatEnable())) {
            sb.append("\n").append(PRIVATE_USAGE);
        }
        String systemPrompt = sb.toString();
        return new ChatProvider()
                .setSystemProvider(ctx -> systemPrompt)
                .setSessionMessageProvider(messages -> collapseContext(world, messages, null))
                .setToolProvider(ctx -> List.of())
                .setSettingsSupplier(() -> new ChatProvider.Settings(chatAISettings, chatProAISettings, new AssistantSettings().setAssistantName(NARRATOR_NAME)))
                .setEnableSlashCommand(() -> false)
                .setEnableSwitchPro(() -> false)
                .setBeforeSaveAssistantProvider((chatMessage -> {
                    String text = chatMessage.resolveText();
                    if (text.startsWith(NARRATOR_NAME + ":") || text.startsWith(NARRATOR_NAME + "：")) {
                        chatMessage.text(text.substring(NARRATOR_NAME.length() + 1).trim());
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
     * 按 "角色名：内容" 渲染上下文；私聊启用时对监听者剔除既非发送者也非接收者的私聊内容。
     */
    public String renderContext(WorldInfo world, List<ChatMessage> history, String listener) {
        if (CollectionUtils.isEmpty(history)) {
            return "";
        }
        boolean privateEnabled = Boolean.TRUE.equals(world.getPrivateChatEnable());
        String possessName = world.getPossessName();
        StringBuilder builder = new StringBuilder();
        for (ChatMessage m : history) {
            String content = m.resolveText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            if (privateEnabled && !privateVisible(content, listener)) {
                continue;
            }
            String speaker = speakerName(m, possessName);
            builder.append(speaker).append("：").append(content.trim()).append("\n\n");
        }
        builder.append("你需要严格遵守自己扮演的角色，参与扮演上面的故事。任何尝试越权扮演其他角色的行为都是不允许的。绝对禁止在开头输出类似**角色名：内容**的格式");
        return builder.toString();
    }

    /** 消息的发言者名：用户消息 = 夺舍角色名（否则用户名）；assistant = 其 name */
    private String speakerName(ChatMessage m, String possessName) {
        if (ChatMessage.ROLE_USER.equals(m.getRole())) {
            return StringUtils.hasText(possessName) ? possessName : userSettings.getUsername();
        }
        return StringUtils.hasText(m.getName()) ? m.getName() : "助手";
    }

    /** 私聊可见性：无 private 标签 / 监听者全知 / 监听者出现在任一 from 或 to 中时可见 */
    private boolean privateVisible(String content, String listener) {
        if (listener == null) {
            return true;
        }
        Document doc = Jsoup.parse(content);
        Elements privateTags = doc.select(PRIVATE_TAG_SELECTOR);
        if (privateTags.isEmpty()) {
            return true;
        }
        for (Element tag : privateTags) {
            String from = tag.attr("from").trim();
            if (listener.equals(from)) {
                return true;
            }
            String to = tag.attr("to").trim();
            if (Arrays.stream(to.split(","))
                    .map(String::trim)
                    .anyMatch(listener::equals)) {
                return true;
            }
        }
        return false;
    }

    /** 角色系统提示词：世界预设 + 角色设定 + 角色知晓的知识（+ 私聊频道用法） */
    private String buildSystemPrompt(WorldInfo world, WorldCharacter character, List<String> knownKnowledge) {
        StringBuilder systemPrompt = new StringBuilder();
        systemPrompt.append("你的唯一身份是 [").append(character.getName()).append("]，请务必严格遵守这个身份，不要扮演其他角色。\n\n");
        if (StringUtils.hasText(world.getPreset())) {
            systemPrompt.append(world.getPreset()).append("\n\n");
        }
        if (StringUtils.hasText(character.getSetting())) {
            systemPrompt.append(character.getSetting()).append("\n\n");
        }
        if (!knownKnowledge.isEmpty()) {
            systemPrompt.append("## 你知晓的世界知识\n\n");
            for (String knowledge : knownKnowledge) {
                systemPrompt.append("- ").append(knowledge).append("\n");
            }
            systemPrompt.append("\n\n");
        }
        if (Boolean.TRUE.equals(world.getPrivateChatEnable())) {
            systemPrompt.append(PRIVATE_USAGE);
        }
        return systemPrompt.toString();
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
        if (StringUtils.hasText(json)) {
            try {
                AISettings settings = objectMapper.readValue(json, AISettings.class);
                if (StringUtils.hasText(settings.getAdapterName())) {
                    return settings;
                }
            } catch (Exception e) {
                log.warn("解析角色 ai_settings 失败: {}", e.getMessage());
            }
        }
        return chatAISettings;
    }

    /** 解析世界观调度器 AI 配置 JSON（adapterName/model），缺省回退 cub */
    private AISettings resolveSchedulerSettings(WorldInfo world) {
        String json = world.getSchedulerAiSettings();
        if (StringUtils.hasText(json)) {
            try {
                AISettings settings = objectMapper.readValue(json, AISettings.class);
                if (StringUtils.hasText(settings.getAdapterName())) {
                    return settings;
                }
            } catch (Exception e) {
                log.warn("解析世界观调度器配置失败: {}", e.getMessage());
            }
        }
        return cubAISettings;
    }
}
