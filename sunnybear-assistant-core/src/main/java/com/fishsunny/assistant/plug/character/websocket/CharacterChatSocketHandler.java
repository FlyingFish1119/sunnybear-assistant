package com.fishsunny.assistant.plug.character.websocket;

/*
 * @Usage 角色对话 WebSocket 处理器 —— 在对话时注入角色的预设 + 角色设定作为系统提示词
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13 10:15
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.mvc.service.ChatSessionService;
import com.fishsunny.assistant.plug.character.db.BattleDbManager;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.repository.CharacterInfoRepository;
import com.fishsunny.assistant.plug.character.service.CharacterChatSelectService;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.plug.character.service.CharacterSessionBindings;
import com.fishsunny.assistant.plug.character.tool.glossary.QueryGlossaryTool;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.settings.AssistantSettings;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.SessionMessageBus;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import com.fishsunny.assistant.websocket.processor.TempChatProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component("characterChatSocketHandler")
public class CharacterChatSocketHandler extends ChatWebSocketHandler {

    private final CharacterInfoRepository characterInfoRepository;
    private final ChatSessionService chatSessionService;
    private final CharacterGlossaryService glossaryService;
    private final BattleDbManager battleDbManager;
    private final CharacterChatSelectService chatSelectService;

    @Autowired
    public CharacterChatSocketHandler(ServiceProcessor serviceProcessor,
                                       TempChatProcessor tempChatProcessor,
                                       ChatProcessor chatProcessor,
                                       TaskExecutor chatAsyncExecutor,
                                       SessionMessageBus sessionMessageBus,
                                       CharacterInfoRepository characterInfoRepository,
                                       ChatSessionService chatSessionService,
                                       CharacterGlossaryService glossaryService,
                                       ObjectMapper objectMapper,
                                       BattleDbManager battleDbManager,
                                       CharacterChatSelectService chatSelectService) {
        super(serviceProcessor, tempChatProcessor, chatProcessor, chatAsyncExecutor, objectMapper, sessionMessageBus);
        this.characterInfoRepository = characterInfoRepository;
        this.chatSessionService = chatSessionService;
        this.glossaryService = glossaryService;
        this.battleDbManager = battleDbManager;
        this.chatSelectService = chatSelectService;
    }

    @Override
    public String sessionType() {
        return CharacterSessionBindings.SESSION_TYPE;
    }

    @Override
    public boolean enableSwitchPro() {
        // 角色会话固定使用角色自己的模型，不允许自动切换 pro
        return false;
    }

    /**
     * 会话感知版 ChatProvider —— 角色的 AI 设置 / 助手名 / 头像按会话挂载，不再覆写全局 settings。
     * 复用基类的 chatToAiProvider(ChatSession) 缝（ChatWebSocketHandler 真正执行对话处调用）。
     */
    @Override
    public ChatProvider chatToAiProvider(ChatSession chatSession) {
        CharacterInfo character = getCharacterInfo(chatSession);
        AISettings charAi = parseCharacterAiSettings(character);

        // 角色配置了 adapter + model 才使用角色自己的 AI 设置；否则 chat/chatPro 返回 null，
        // 由 ChatProcessor 自动回落全局 chat/chat_pro bean（保留“未配置则继承全局”的语义）
        boolean useCharAi = charAi != null
                && StringUtils.hasText(charAi.getAdapterName())
                && StringUtils.hasText(charAi.getModel());
        AISettings effectiveCharAi = useCharAi ? charAi : null;

        // 1. 系统提示词：preset + 角色设定（aiSettings.prompt），需要时追加角色词条表
        Function<ChatProvider.SystemProviderContext, String> systemProvider = context -> {
            StringBuilder combined = new StringBuilder();
            String preset = character.getPreset();
            if (StringUtils.hasText(preset)) {
                combined.append(preset).append("\n\n");
            }

            if (charAi != null && StringUtils.hasText(charAi.getPrompt())) {
                combined.append(charAi.getPrompt());
            }

            if (toolsEnabled(character, QueryGlossaryTool.NAME)) {
                combined.append("\n\n## 角色词条表 (Character Glossary)\n");
                List<CharacterGlossary> glossaries = glossaryService.listByCharacterId(character.getId());
                if (glossaries != null && !glossaries.isEmpty()) {
                    combined.append("以下是与该角色相关的术语和背景设定，在对话中请参考这些词条，词条的详细内容通过工具查询获得：\n");
                    for (CharacterGlossary g : glossaries) {
                        combined.append("- **").append(g.getKeyword()).append("**");
                        if (StringUtils.hasText(g.getDesc())) {
                            combined.append("：").append(g.getDesc());
                        }
                        combined.append("\n");
                    }
                    log.debug("角色词条注入成功 [characterId={}, count={}]", character.getId(), glossaries.size());
                } else {
                    combined.append("**当前无可用的词条**");
                }
            }

            log.debug("角色对话系统提示词已构建 [characterId={}, length={}]", character.getId(), combined.length());
            return combined.toString();
        };

        // 2. 工具白名单过滤：严格按照 character_info.tools JSON 配置
        Function<ChatProvider.ToolProviderContext, List<StandardToolRegister>> toolProvider = ctx -> {
            Map<String, Boolean> toolsMap = toolsEnabledMap(character);
            return ctx.toolRegisters().stream()
                    .filter(tr -> toolsMap.getOrDefault(tr.getFunction().getName(), false))
                    .collect(Collectors.toList());
        };

        // 3. Context Hook：注入当前角色 + 会话战斗数据库 DataSource
        Function<Map<String, Object>, Map<String, Object>> contextProvider = ctx -> {
            ctx.put("character", character);
            DataSource battleDs = battleDbManager.getDataSource(chatSession.getId());
            if (battleDs != null) {
                ctx.put("battleDataSource", battleDs);
            }
            return ctx;
        };

        return new ChatProvider()
                .setSystemProvider(systemProvider)
                .setToolProvider(toolProvider)
                .setContextProvider(contextProvider)
                // 主对话喂给对话 AI 前，剥掉历史 assistant 正文里已持久化的快捷选项标签，避免对话 AI 看到选项
                .setSessionMessageProvider(CharacterChatSelectService::stripTagsFromMessages)
                // assistant 回复生成后、落库前：启用 chat_select 时用 mission 模型生成新选项并追加
                .setBeforeSaveAssistantProvider(this::beforeSaveAssistant)
                // 角色会话固定用自己的模型（chat/chatPro 都指向角色自己的 aiSettings），助手名/头像取角色资料
                .setSettingsSupplier(() -> new ChatProvider.Settings(effectiveCharAi, effectiveCharAi,
                        new AssistantSettings()
                                .setAssistantName(character.getName())
                                .setAvatar(character.getAvatar())))
                .setEnableSlashCommand(() -> false);
    }

    /** 解析角色 aiSettings JSON；为空或解析失败返回 null */
    private AISettings parseCharacterAiSettings(CharacterInfo character) {
        if (character == null || !StringUtils.hasText(character.getAiSettings())) {
            return null;
        }
        try {
            return objectMapper.readValue(character.getAiSettings(), AISettings.class);
        } catch (Exception e) {
            log.warn("解析角色 [{}] aiSettings JSON 失败: {}", character.getId(), e.getMessage());
            return null;
        }
    }

    /** 某工具开关是否开启（tools JSON 中该 key 为 true） */
    private boolean toolsEnabled(CharacterInfo character, String toolName) {
        return Boolean.TRUE.equals(toolsEnabledMap(character).get(toolName));
    }

    /** 解析角色 tools JSON（{"toolName": true/false}）；空/解析失败返回空 map */
    private Map<String, Boolean> toolsEnabledMap(CharacterInfo character) {
        Map<String, Boolean> toolsMap = new HashMap<>();
        if (character != null && StringUtils.hasText(character.getTools())) {
            try {
                toolsMap = objectMapper.readValue(character.getTools(), new TypeReference<Map<String, Boolean>>() {});
            } catch (Exception e) {
                log.warn("解析角色 [{}] 的 tools JSON 失败: {}", character.getId(), e.getMessage());
            }
        }
        return toolsMap;
    }

    /**
     * 落库前钩子：为最新的 assistant 回复生成并追加快捷选项。
     * 仅对“无工具调用的最终回复”生效；中间的工具调用轮直接放行。
     * 生成/解析失败时静默跳过，不影响主对话。
     */
    private ChatMessage beforeSaveAssistant(ChatMessage readyToSave) {
        try {
            if (readyToSave.getToolCalls() != null && !readyToSave.getToolCalls().isEmpty()) {
                return readyToSave;
            }
            // 没有正文的回复（如纯异常兜底）不生成选项
            if (!StringUtils.hasText(readyToSave.resolveText())) {
                return readyToSave;
            }
            String sessionId = readyToSave.getSessionId();
            if (!StringUtils.hasText(sessionId)) {
                return readyToSave;
            }
            CharacterInfo character = getCharacterBySessionId(sessionId);
            if (character == null) {
                return readyToSave;
            }
            CharacterChatSelectService.ChatSelectConfig config = chatSelectService.parseConfig(character);
            if (!config.enable()) {
                return readyToSave;
            }
            String markup = chatSelectService.generateOptions(sessionId, readyToSave, config.format(), character);
            if (StringUtils.hasText(markup)) {
                CharacterChatSelectService.appendChatSelect(readyToSave, markup);
                log.debug("已为会话 [{}] 追加快捷选项", sessionId);
            }
        } catch (Exception e) {
            log.warn("生成快捷选项失败: {}", e.getMessage());
        }
        return readyToSave;
    }

    /** 通过会话查询绑定的角色；未绑定返回 null（不阻塞等待） */
    private CharacterInfo getCharacterBySessionId(String sessionId) {
        try {
            ChatSession session = chatSessionService.findById(sessionId);
            String characterId = CharacterSessionBindings.resolveCharacterId(session);
            if (characterId == null) {
                return null;
            }
            return characterInfoRepository.selectById(characterId);
        } catch (Exception e) {
            log.warn("查询会话 [{}] 绑定角色失败: {}", sessionId, e.getMessage());
            return null;
        }
    }

    private CharacterInfo getCharacterInfo(ChatSession ctxSession) {
        // 一次性注册已取代"先 create、后 HTTP bind"的延时绑定：新会话的 extension 在 create 时即随会话落库，
        // 旧绑定也已由启动迁移写入 extension，因此不再需要轮询等待。传参会话若已带角色绑定直接取用，
        // 否则按 id 回读一次兜底。
        String characterId = CharacterSessionBindings.resolveCharacterId(ctxSession);
        if (characterId == null) {
            ChatSession dbSession = chatSessionService.findById(ctxSession.getId());
            characterId = CharacterSessionBindings.resolveCharacterId(dbSession);
        }
        if (characterId == null) {
            throw new RuntimeException("会话未绑定角色，请从角色页发起对话");
        }

        CharacterInfo character = characterInfoRepository.selectById(characterId);
        if (character == null) {
            throw new RuntimeException("角色不存在: " + characterId);
        }
        return character;
    }
}
