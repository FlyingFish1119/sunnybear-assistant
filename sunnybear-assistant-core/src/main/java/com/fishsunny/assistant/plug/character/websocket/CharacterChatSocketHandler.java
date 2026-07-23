package com.fishsunny.assistant.plug.character.websocket;

/*
 * @Usage 角色对话 WebSocket 处理器 —— 在对话时注入角色的预设 + 角色设定作为系统提示词
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13 10:15
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;
import com.fishsunny.assistant.plug.character.repository.CharacterInfoRepository;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.plug.character.db.BattleDbManager;
import com.fishsunny.assistant.plug.character.service.CharacterSessionMappingService;
import com.fishsunny.assistant.plug.character.tool.glossary.QueryGlossaryTool;
import com.fishsunny.assistant.settings.AISettings;
import com.fishsunny.assistant.websocket.ChatProvider;
import com.fishsunny.assistant.websocket.ChatWebSocketHandler;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import com.fishsunny.assistant.websocket.processor.ServiceProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketSession;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component()
public class CharacterChatSocketHandler extends ChatWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CharacterChatSocketHandler.class);

    private final CharacterInfoRepository characterInfoRepository;
    private final CharacterSessionMappingService mappingService;
    private final CharacterGlossaryService glossaryService;
    private final ObjectMapper objectMapper;
    private final BattleDbManager battleDbManager;

    @Autowired
    public CharacterChatSocketHandler(ServiceProcessor serviceProcessor,
                                       ChatProcessor chatProcessor,
                                       TaskExecutor chatAsyncExecutor,
                                       CharacterInfoRepository characterInfoRepository,
                                       CharacterSessionMappingService mappingService,
                                       CharacterGlossaryService glossaryService,
                                       ObjectMapper objectMapper,
                                       BattleDbManager battleDbManager) {
        super(serviceProcessor, chatProcessor, chatAsyncExecutor, objectMapper);
        this.characterInfoRepository = characterInfoRepository;
        this.mappingService = mappingService;
        this.glossaryService = glossaryService;
        this.objectMapper = objectMapper;
        this.battleDbManager = battleDbManager;
    }

    @Override
    protected boolean isProModelEnabled() {
        return false;
    }

    @Override
    public ChatProvider chatToAiProvider() {

        Function<ChatProvider.SystemProviderContext, String> systemProvider = context -> {
            ChatSession chatSession = context.chatSession();

            // 1. 通过 sessionId 找到绑定的角色
            CharacterInfo character = getCharacterInfo(chatSession);

            // 3. 拼接 preset + 角色设定（aiSettings.prompt）
            StringBuilder combined = new StringBuilder();
            String preset = character.getPreset();
            if (StringUtils.hasText(preset)) {
                combined.append(preset).append("\n\n");
            }

            String aiSettingsJson = character.getAiSettings();
            if (StringUtils.hasText(aiSettingsJson)) {
                try {
                    AISettings charAi = objectMapper.readValue(aiSettingsJson, AISettings.class);
                    if (StringUtils.hasText(charAi.getPrompt())) {
                        combined.append(charAi.getPrompt());
                    }
                } catch (Exception e) {
                    throw new RuntimeException("角色设定 JSON 解析失败");
                }
            }

            Map<String, Boolean> tools = new HashMap<>();
            if (StringUtils.hasText(character.getTools())) {
                try {
                    tools = objectMapper.readValue(character.getTools(), new TypeReference<Map<String, Boolean>>() {});
                } catch (Exception e) {
                    throw new RuntimeException("角色工具 JSON 解析失败");
                }
            }

            if (Boolean.TRUE.equals(tools.get(QueryGlossaryTool.NAME))) {
                // 4. 注入角色词条表（keyword + desc）
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

        // 工具白名单过滤：严格按照 character_info.tools JSON 配置
        Function<ChatProvider.ToolProviderContext, List<StandardToolRegister>> toolProvider = ctx -> {
            List<StandardToolRegister> toolRegisters = ctx.toolRegisters();
            ChatSession ctxSession = ctx.chatSession();

            CharacterInfo character = getCharacterInfo(ctxSession);

            String toolsJson = character.getTools();

            try {
                JavaType mapType = objectMapper.getTypeFactory().constructMapType(Map.class, String.class, Boolean.class);
                Map<String, Boolean> toolsMap = objectMapper.readValue(toolsJson, mapType);

                return toolRegisters.stream()
                        .filter(tr -> toolsMap.getOrDefault(tr.getFunction().getName(), false))
                        .collect(Collectors.toList());
            } catch (Exception e) {
                log.warn("解析角色 [{}] 的 tools JSON 失败: {}", character.getId(), e.getMessage());
                return List.of();
            }
        };

        // Context Hook
        Function<Map<String, Object>, Map<String, Object>> contextProvider = ctx -> {
            ChatSession ctxSession = (ChatSession) ctx.get("chatSession");
            if (ctxSession == null) {
                throw new RuntimeException("未找到会话");
            }

            // 获取当前会话绑定的角色
            CharacterInfo character = getCharacterInfo(ctxSession);
            ctx.put("character", character);

            // 将当前 session 的战斗数据库 DataSource 注入工具上下文
            DataSource battleDs = battleDbManager.getDataSource(ctxSession.getId());
            if (battleDs != null) {
                ctx.put("battleDataSource", battleDs);
            }
            return ctx;
        };

        return new ChatProvider()
                .setSystemProvider(systemProvider)
                .setToolProvider(toolProvider)
                .setContextProvider(contextProvider);
    }

    private CharacterInfo getCharacterInfo(ChatSession ctxSession) {
        // 通过 session 查角色绑定，MODE_CREATE 时前端 HTTP bind 请求可能还在路上，轮询等待
        CharacterSessionMapping mapping = null;
        for (int i = 0; i < 50; i++) {
            mapping = mappingService.findBySessionId(ctxSession.getId());
            if (mapping != null) break;
            try { Thread.sleep(100); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("等待角色绑定时被中断");
            }
        }
        if (mapping == null) {
            throw new RuntimeException("会话未绑定角色");
        }

        CharacterInfo character = characterInfoRepository.selectById(mapping.getCharacterId());
        if (character == null) {
            throw new RuntimeException("角色不存在");
        }
        return character;
    }
}
