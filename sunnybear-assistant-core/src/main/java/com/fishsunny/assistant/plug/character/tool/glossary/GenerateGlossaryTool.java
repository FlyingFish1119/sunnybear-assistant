package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条生成工具 —— AI 通过 prompt 描述场景，结合角色设定信息，自动生成永久性词条（keyword + desc + content）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13 17:26
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;
import com.fishsunny.assistant.plug.character.service.CharacterGlossaryService;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import com.fishsunny.assistant.plug.character.service.CharacterSessionMappingService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(CharacterGlossaryToolKit.class)
@ConditionalOnExpression("${plug.character.tool.glossary.enable:false} && ${plug.character.tool.glossary.generate-glossary.enable:true}")
public class GenerateGlossaryTool implements ToolHandler {

    public static final String NAME = "character_glossary_generate_tool";

    private static final Logger log = LoggerFactory.getLogger(GenerateGlossaryTool.class);

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final CharacterInfoService characterInfoService;
    private final CharacterGlossaryService glossaryService;

    public GenerateGlossaryTool(ObjectMapper objectMapper,
                                @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                                ChatHttpHandler chatHttpHandler,
                                CharacterInfoService characterInfoService,
                                CharacterGlossaryService glossaryService) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.chatHttpHandler = chatHttpHandler;
        this.characterInfoService = characterInfoService;
        this.glossaryService = glossaryService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        为当前角色生成并永久保存一条词条。用于记录对话中出现的新设定、事件、关系等信息。生成后可通过词条查询工具检索。""")
                .setRequired(List.of("prompt"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("prompt", "string",
                                "场景描述，包含你想要固化为词条的所有信息。越详细越好，例如：事件经过、人物关系、地点特征、组织背景等。")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        if (!StringUtils.hasText(arguments.getPrompt())) {
            throw new ToolExecutor.ToolExecuteException("参数 prompt 不能为空");
        }

        // 1. 从上下文获取当前会话
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        if (chatSession == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话信息");
        }

        // 3. 获取角色信息
        CharacterInfo character = (CharacterInfo) context.get("character");
        if (character == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前角色信息");
        }

        // 4. 提取角色设定信息（aiSettings.prompt + preset）
        String characterSetting = buildCharacterSetting(character);

        // 5. 调用 AI 生成词条
        String generatedJson;
        try {
            generatedJson = generateGlossary(characterSetting, arguments.getPrompt());
        } catch (Exception e) {
            log.error("AI 生成词条失败", e);
            throw new ToolExecutor.ToolExecuteException("AI 生成词条失败: " + e.getMessage());
        }

        // 6. 解析 AI 返回的 JSON
        GeneratedGlossary generated;
        try {
            generated = parseGeneratedGlossary(generatedJson);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("解析 AI 生成的词条失败: " + e.getMessage());
        }

        if (!StringUtils.hasText(generated.getKeyword())) {
            throw new ToolExecutor.ToolExecuteException("AI 生成的词条缺少关键词（keyword）");
        }
        if (!StringUtils.hasText(generated.getContent())) {
            throw new ToolExecutor.ToolExecuteException("AI 生成的词条缺少内容（content）");
        }

        // 7. 持久化保存
        CharacterGlossary glossary = new CharacterGlossary()
                .setCharacterId(character.getId())
                .setKeyword(generated.getKeyword().trim())
                .setDesc(StringUtils.hasText(generated.getDesc()) ? generated.getDesc().trim() : "")
                .setContent(generated.getContent().trim());

        try {
            CharacterGlossary saved = glossaryService.create(glossary);
            log.info("词条生成成功 [characterId={}, keyword={}]", character.getId(), saved.getKeyword());
            return new ToolExecutor.ToolExecuteResponse(NAME,
                    "词条 `" + saved.getKeyword() + "` 已成功生成并保存。");
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("保存词条失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    // ==================== 内部方法 ====================

    /**
     * 构建角色设定文本（preset + aiSettings.prompt），供 AI 生成词条时参考
     */
    private String buildCharacterSetting(CharacterInfo character) {
        StringBuilder sb = new StringBuilder();
        String preset = character.getPreset();
        if (StringUtils.hasText(preset)) {
            sb.append(preset).append("\n\n");
        }

        String aiSettingsJson = character.getAiSettings();
        if (StringUtils.hasText(aiSettingsJson)) {
            try {
                AISettings charAi = objectMapper.readValue(aiSettingsJson, AISettings.class);
                if (StringUtils.hasText(charAi.getPrompt())) {
                    sb.append(charAi.getPrompt());
                }
            } catch (Exception e) {
                log.warn("解析角色 [{}] 的 aiSettings 失败: {}", character.getId(), e.getMessage());
            }
        }
        return sb.toString();
    }

    /**
     * 调用 AI 生成词条。
     * <p>
     * missionAI 的 prompt 不是固定的（空才是常态），因此不使用 missionAISettings.getPrompt()，
     * 而是由工具自身构建词条生成专用的 system prompt。
     */
    private String generateGlossary(String characterSetting, String userPrompt) throws Exception {
        ChatRequest request = new ChatRequest()
                .setMessages(List.of(
                        new ChatMessage().system(buildSystemPrompt()),
                        new ChatMessage().user(buildUserPrompt(characterSetting, userPrompt))
                ))
                .loadSettings(new AISettings().copy(missionAISettings).setResponseFormat("json_object"));

        AtomicReference<String> afterResolve = new AtomicReference<>("");
        chatHttpHandler.translate(
                UUID.randomUUID().toString(),
                missionAISettings.getAdapterName(),
                request,
                missionAISettings.getStream() != null ? missionAISettings.getStream() : false,
                null,
                (result, lastRes) -> afterResolve.set(result.content())
        );
        return afterResolve.get();
    }

    /**
     * 构建 system prompt：词条生成的任务指令 + 输出格式要求
     */
    private String buildSystemPrompt() {
        return """
                你是一个专门为角色扮演场景生成结构化"词条"的 AI。
                你的任务是根据提供的角色设定和场景描述，生成一条可供后续查询使用的永久性词条。

                词条用于记录角色的世界观信息，包括但不限于：
                - 新发生的事件
                - 人物关系的变化
                - 地点/组织的特征
                - 术语或世界观设定

                ## 输出要求
                请严格按照以下 JSON 格式输出，不要包含 markdown 代码块标记或其他额外内容：

                {
                    "keyword": "词条关键词（简洁明了，3-20字，用于精确查询）",
                    "desc": "词条简短描述（一句话概括词条内容，用于在词条列表中快速预览，不超过100字）",
                    "content": "词条完整内容（详细描述，包含所有相关信息，Markdown 格式）"
                }

                要求：
                1. keyword 要简洁准确，便于后续检索，应能代表词条的核心主题
                2. desc 是对词条的一句话概括，对AI后续决定是否查询该词条有帮助
                3. content 要详尽完整，使用 Markdown 格式组织，将场景描述中的所有重要信息都纳入
                4. 只输出 JSON，不要包含 ```json 等标记
                """;
    }

    /**
     * 构建 user prompt：角色设定 + 场景描述
     */
    private String buildUserPrompt(String characterSetting, String userPrompt) {
        return """
                ## 角色设定
                ${characterSetting}

                ## 场景描述
                ${userPrompt}
                """
                .replace("${characterSetting}", StringUtils.hasText(characterSetting) ? characterSetting : "（无角色设定）")
                .replace("${userPrompt}", userPrompt);
    }

    /**
     * 解析 AI 返回的 JSON，提取 keyword / desc / content
     */
    private GeneratedGlossary parseGeneratedGlossary(String aiResponse) throws Exception {
        // 尝试去除可能的 markdown 代码块标记
        String jsonStr = aiResponse.trim();
        if (jsonStr.startsWith("```")) {
            int start = jsonStr.indexOf("\n");
            int end = jsonStr.lastIndexOf("```");
            if (start > 0 && end > start) {
                jsonStr = jsonStr.substring(start + 1, end).trim();
            }
        }

        return objectMapper.readValue(jsonStr, GeneratedGlossary.class);
    }

    @Data
    private static class Arguments {
        private String prompt;
    }

    @Data
    @Accessors(chain = true)
    private static class GeneratedGlossary {
        private String keyword;
        private String desc;
        private String content;
    }
}
