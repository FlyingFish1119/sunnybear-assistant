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
import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.settings.UserSettings;
import com.fishsunny.assistant.variable.ControlSign;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@Slf4j
public class WorldGroupChatService {

    /** 旁白内置角色名（narration_enable 时作为调度器候选之一） */
    public static final String NARRATOR_NAME = "旁白";

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
                .collect(Collectors.toMap(WorldCharacter::getName, c -> c, (a, b) -> a));
        List<String> candidates = candidateNames(world, characters);
        if (candidates.isEmpty()) {
            log.warn("世界观 [{}] 没有任何可发言的角色，跳过本轮", world.getId());
            return;
        }

        int maxRounds = world.getMaxRounds() == null ? 5 : world.getMaxRounds();
        String possessName = world.getPossessName();

        for (int round = 0; round < maxRounds; round++) {
            List<ChatMessage> history = chatMessageService.getConversationHistory(chatSession.getId());
            // 调度器只看当前回合（最近一条用户消息往后），轻量省 token
            String contextText = renderContext(world, currentTurnContext(history), null);

            String chosen = selectSpeaker(world, contextText, candidates);
            if (chosen == null) {
                log.info("调度器未给出有效选角，轮次结束 [round={}]", round);
                break;
            }
            // 用户夺舍语义：调度器选中夺舍角色时不再生成，直接把回合交还用户
            if (StringUtils.hasText(possessName) && possessName.equals(chosen)) {
                log.info("调度器选中夺舍角色 [{}]，停止本轮生成", chosen);
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
            session.sendMessage(new TextMessage(ControlSign.SIGN_WORLD_ROUND + chatSession.getId() + "|" + chosen));
            // 被选角色看完整会话历史（sessionMessageProvider 折叠成单条 user 消息）
            chatProcessor.chatToAi(history, chatSession, session, provider);
        }
    }

    /**
     * 候选发言者列表：世界内全部角色 + （旁白启用时）内置旁白。
     */
    public List<String> candidateNames(WorldInfo world, List<WorldCharacter> characters) {
        List<String> names = new ArrayList<>();
        if (characters != null) {
            for (WorldCharacter c : characters) {
                if (StringUtils.hasText(c.getName())) {
                    names.add(c.getName());
                }
            }
        }
        if (Boolean.TRUE.equals(world.getNarrationEnable())) {
            names.add(NARRATOR_NAME);
        }
        return names;
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
     *
     * @return 选中角色名；无法决策 / 解析失败 / 非法名字时返回 null
     */
    public String selectSpeaker(WorldInfo world, String contextText, List<String> candidates) throws Exception {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        AISettings schedulerSettings = resolveSchedulerSettings(world);
        String candidateDesc = String.join("、", candidates);
        String system = """
                你是群聊场景调度器。根据最近的对话内容与候选发言者，决定下一位发言者。

                要求：
                - 只输出一个 JSON 对象，格式为 {"name": "发言者名字"}，不要输出任何其他内容。
                - 名字必须是候选发言者中的某一个。
                - 在剧情推进、需要回应他人、出现新的冲突或转场时，选择合适的角色；旁白用于场景描述与氛围渲染。

                候选发言者：%s
                """.formatted(candidateDesc);

        ChatRequest request = new ChatRequest()
                .loadSettings(new AISettings().copy(schedulerSettings).json())
                .setMessages(List.of(
                        new ChatMessage().system(system),
                        new ChatMessage().user("对话内容：\n" + contextText)
                ));

        AtomicReference<String> chosen = new AtomicReference<>();
        chatHttpHandler.translate(UUID.randomUUID().toString(), schedulerSettings.getAdapterName(),
                request, false, null,
                (result, lastRes) -> chosen.set(parseName(result.content())));

        String name = chosen.get();
        if (name != null && !candidates.contains(name)) {
            log.warn("调度器返回了不在候选列表中的名字 [{}]，忽略", name);
            return null;
        }
        return name;
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
                .setSessionMessageProvider(msgs -> collapseContext(world, msgs, listener))
                .setToolProvider(ctx -> List.of())
                .setSettingsSupplier(() -> new ChatProvider.Settings(
                        charAi, charAi, new AssistantSettings().setAssistantName(listener)));
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
                .setSessionMessageProvider(msgs -> collapseContext(world, msgs, null))
                .setToolProvider(ctx -> List.of())
                .setSettingsSupplier(() -> new ChatProvider.Settings(
                        chatAISettings, chatProAISettings, new AssistantSettings().setAssistantName(NARRATOR_NAME)));
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
        for (ChatMessage m : history) {
            if (ChatMessage.ROLE_USER.equals(m.getRole())) {
                lastUserId = m.getId();
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
        if (history == null || history.isEmpty()) {
            return "";
        }
        boolean privateEnabled = Boolean.TRUE.equals(world.getPrivateChatEnable());
        String possessName = world.getPossessName();
        StringBuilder sb = new StringBuilder();
        for (ChatMessage m : history) {
            String content = m.resolveText();
            if (!StringUtils.hasText(content)) {
                continue;
            }
            if (privateEnabled && !privateVisible(content, listener)) {
                continue;
            }
            sb.append(speakerName(m, possessName)).append("：").append(content.trim()).append("\n");
        }
        return sb.toString();
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
        StringBuilder sb = new StringBuilder();
        if (StringUtils.hasText(world.getPreset())) {
            sb.append(world.getPreset()).append("\n\n");
        }
        if (StringUtils.hasText(character.getSetting())) {
            sb.append(character.getSetting()).append("\n\n");
        }
        if (!knownKnowledge.isEmpty()) {
            sb.append("## 你知晓的世界知识\n");
            for (String k : knownKnowledge) {
                sb.append("- ").append(k).append("\n");
            }
            sb.append("\n");
        }
        if (Boolean.TRUE.equals(world.getPrivateChatEnable())) {
            sb.append(PRIVATE_USAGE);
        }
        return sb.toString();
    }

    /** 角色知晓的知识（title：content），来自 world_knowledge + world_knowledge_character */
    private List<String> knownKnowledge(WorldInfo world, WorldCharacter character) {
        List<WorldKnowledge> knowledges = worldKnowledgeService.findByWorldId(world.getId());
        List<String> result = new ArrayList<>();
        if (knowledges == null) {
            return result;
        }
        for (WorldKnowledge k : knowledges) {
            if (k.getCharacterIds() == null || !k.getCharacterIds().contains(character.getId())) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (StringUtils.hasText(k.getTitle())) {
                sb.append(k.getTitle());
            }
            if (StringUtils.hasText(k.getContent())) {
                if (!sb.isEmpty()) {
                    sb.append("：");
                }
                sb.append(k.getContent());
            }
            if (!sb.isEmpty()) {
                result.add(sb.toString());
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
