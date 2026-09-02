package com.fishsunny.assistant.plug.character.tool.dice;

/*
 * @Usage D20 检定工具 —— 投掷 d20 进行技能/属性检定，由 MissionAI 根据检定结果生成叙事文字
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.mvc.service.ChatMessageService;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;
import com.fishsunny.assistant.plug.character.service.CharacterInfoService;
import com.fishsunny.assistant.plug.character.service.CharacterSessionMappingService;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(DiceToolKit.class)
@ConditionalOnExpression("${plug.character.tool.dice.enable:false} && ${plug.character.tool.dice.d20.enable:true}")
public class D20Tool implements ToolHandler {

    public static final String NAME = "d20_check";

    private static final Logger log = LoggerFactory.getLogger(D20Tool.class);
    private static final int MAX_HISTORY_ROUNDS = 3;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final CharacterSessionMappingService mappingService;
    private final CharacterInfoService characterInfoService;
    private final ChatMessageService messageService;

    public D20Tool(ObjectMapper objectMapper,
                   @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                   ChatHttpHandler chatHttpHandler,
                   CharacterSessionMappingService mappingService,
                   CharacterInfoService characterInfoService,
                   ChatMessageService messageService) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.chatHttpHandler = chatHttpHandler;
        this.mappingService = mappingService;
        this.characterInfoService = characterInfoService;
        this.messageService = messageService;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        进行 D20 检定：投 20 面骰 + 属性补正 vs 目标难度，判定动作成败。支持优势/劣势。检定结果会生成叙事描述。""")
                .setRequired(List.of("difficulty", "event"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("difficulty", "integer",
                                "目标难度 DC，范围 1-30。结果 >= DC 则成功"),
                        new ToolRegister.Parameters("event", "string",
                                "事件描述，说明角色正在尝试做什么。如'试图撬开上锁的宝箱'"),
                        new ToolRegister.Parameters("modifier", "integer",
                                "属性补正（可选），如力量+3填3，敏捷-1填-1。默认0"),
                        new ToolRegister.Parameters("advantage", "string",
                                "优势/劣势（可选）。'advantage'=投两次取高，'disadvantage'=投两次取低")
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

        // 校验参数
        if (arguments.getDifficulty() == null || arguments.getDifficulty() < 1 || arguments.getDifficulty() > 30) {
            throw new ToolExecutor.ToolExecuteException("难度（difficulty）必须在 1-30 之间");
        }
        if (!StringUtils.hasText(arguments.getEvent())) {
            throw new ToolExecutor.ToolExecuteException("事件描述（event）不能为空");
        }

        int difficulty = arguments.getDifficulty();
        int modifier = arguments.getModifier() != null ? arguments.getModifier() : 0;
        String event = arguments.getEvent().trim();

        // 判定优势/劣势
        String adv = parseAdvantage(arguments.getAdvantage());

        // ========== 掷骰子 ==========
        int roll;
        String rollDetail;
        if ("advantage".equals(adv)) {
            int r1 = rollD20();
            int r2 = rollD20();
            roll = Math.max(r1, r2);
            rollDetail = String.format("**优势检定**：投出 %d 和 %d，取高 = %d", r1, r2, roll);
        } else if ("disadvantage".equals(adv)) {
            int r1 = rollD20();
            int r2 = rollD20();
            roll = Math.min(r1, r2);
            rollDetail = String.format("**劣势检定**：投出 %d 和 %d，取低 = %d", r1, r2, roll);
        } else {
            roll = rollD20();
            rollDetail = String.format("**普通检定**：投出 %d", roll);
        }

        // ========== 判定结果 ==========
        int finalResult = roll + modifier;

        String outcome;
        if (roll == 20) {
            outcome = "critical_success";
        } else if (roll == 1) {
            outcome = "critical_failure";
        } else if (finalResult >= difficulty) {
            outcome = "success";
        } else {
            outcome = "failure";
        }

        // ========== 获取上下文信息 ==========
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession != null ? chatSession.getId() : null;

        // 角色设定
        String characterSetting = "";
        if (chatSession != null) {
            CharacterSessionMapping mapping = mappingService.findBySessionId(chatSession.getId());
            if (mapping != null) {
                CharacterInfo character = characterInfoService.findById(mapping.getCharacterId());
                if (character != null) {
                    characterSetting = buildCharacterSetting(character);
                }
            }
        }

        // 最近 3 轮对话历史
        String recentHistory = sessionId != null ? buildRecentHistory(sessionId) : "";

        // ========== 调用 MissionAI 生成叙事 ==========
        String narrative;
        try {
            narrative = generateNarrative(characterSetting, recentHistory, event, difficulty, modifier, roll, finalResult, rollDetail, outcome);
        } catch (Exception e) {
            log.error("MissionAI 生成 D20 叙事失败", e);
            // 降级：返回纯数据结果
            narrative = null;
        }

        // ========== 构建返回结果 ==========
        StringBuilder result = new StringBuilder();
        result.append("🎲 **D20 检定**\n\n");
        result.append("- **事件**：").append(event).append("\n");
        result.append("- **难度 DC**：").append(difficulty).append("\n");
        result.append("- ").append(rollDetail).append("\n");
        if (modifier != 0) {
            result.append("- **属性补正**：").append(modifier > 0 ? "+" : "").append(modifier).append("\n");
            result.append("- **最终结果**：").append(roll).append(" + (").append(modifier > 0 ? "+" : "").append(modifier).append(") = ").append(finalResult).append("\n");
        }
        result.append("- **判定**：");
        switch (outcome) {
            case "critical_success" -> result.append("🌟 大成功！（自然 20）");
            case "critical_failure" -> result.append("💀 大失败！（自然 1）");
            case "success" -> {
                if (modifier != 0) {
                    result.append("✅ 成功（").append(finalResult).append(" >= ").append(difficulty).append("）");
                } else {
                    result.append("✅ 成功（").append(roll).append(" >= ").append(difficulty).append("）");
                }
            }
            case "failure" -> {
                if (modifier != 0) {
                    result.append("❌ 失败（").append(finalResult).append(" < ").append(difficulty).append("）");
                } else {
                    result.append("❌ 失败（").append(roll).append(" < ").append(difficulty).append("）");
                }
            }
        }

        if (narrative != null) {
            result.append("\n\n").append(narrative);
        }

        return new ToolExecutor.ToolExecuteResponse(NAME, result.toString());
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
     * 投掷一个 d20（1-20）
     */
    private int rollD20() {
        return ThreadLocalRandom.current().nextInt(1, 21);
    }

    /**
     * 解析优势/劣势参数。仅 "advantage" 或 "disadvantage" 有效，其他一律视为普通检定。
     */
    private String parseAdvantage(String advantage) {
        if (!StringUtils.hasText(advantage)) {
            return null;
        }
        String trimmed = advantage.trim().toLowerCase();
        if ("advantage".equals(trimmed)) {
            return "advantage";
        }
        if ("disadvantage".equals(trimmed)) {
            return "disadvantage";
        }
        // 多选或无效值 → 普通检定
        log.debug("无效的优势/劣势参数 [{}]，视为普通检定", advantage);
        return null;
    }

    /**
     * 构建角色设定文本（preset + aiSettings.prompt）
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
     * 提取最近 3 轮完整 QA（user → assistant 对）。
     * 从消息历史中倒序提取，每个 user 消息及其紧跟的 assistant 回复为一轮。
     */
    private String buildRecentHistory(String sessionId) {
        try {
            List<ChatMessage> messages = messageService.findBySessionId(sessionId);
            if (messages == null || messages.isEmpty()) {
                return "";
            }

            // 过滤出 user 和 assistant 角色的活跃消息，按时间正序
            List<ChatMessage> filtered = messages.stream()
                    .filter(m -> m.getActive() == null || m.getActive())
                    .filter(m -> "user".equals(m.getRole()) || "assistant".equals(m.getRole()))
                    .sorted((a, b) -> {
                        if (a.getCreateTime() == null && b.getCreateTime() == null) return 0;
                        if (a.getCreateTime() == null) return -1;
                        if (b.getCreateTime() == null) return 1;
                        return a.getCreateTime().compareTo(b.getCreateTime());
                    })
                    .toList();

            // 倒序取最近 3 轮 QA
            List<ChatMessage> recent = new ArrayList<>();
            int rounds = 0;
            for (int i = filtered.size() - 1; i >= 0 && rounds < MAX_HISTORY_ROUNDS; i--) {
                ChatMessage msg = filtered.get(i);
                if ("assistant".equals(msg.getRole())) {
                    recent.add(0, msg); // assistant 插入到前面
                } else if ("user".equals(msg.getRole())) {
                    recent.add(0, msg); // user 插入到前面
                    rounds++; // 每遇到一个 user 计为一轮
                }
            }

            if (recent.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            for (ChatMessage msg : recent) {
                String roleLabel = "user".equals(msg.getRole()) ? "用户" : "助手";
                sb.append("**").append(roleLabel).append("**：").append(msg.resolveText()).append("\n\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("获取会话历史失败: {}", e.getMessage());
            return "";
        }
    }

    /**
     * 调用 MissionAI 生成 D20 检定叙事文字
     */
    private String generateNarrative(String characterSetting, String recentHistory,
                                     String event, int difficulty, int modifier, int roll, int finalResult,
                                     String rollDetail, String outcome) throws Exception {
        ChatRequest request = new ChatRequest()
                .setMessages(List.of(
                        new ChatMessage().system(buildSystemPrompt()),
                        new ChatMessage().user(buildUserPrompt(characterSetting, recentHistory,
                                event, difficulty, modifier, roll, finalResult, rollDetail, outcome))
                ))
                .loadSettings(new AISettings().copy(missionAISettings).json());

        AtomicReference<String> afterResolve = new AtomicReference<>("");
        chatHttpHandler.translate(
                UUID.randomUUID().toString(),
                missionAISettings.getAdapterName(),
                request,
                missionAISettings.getStream() != null ? missionAISettings.getStream() : false,
                null,
                (result, lastRes) -> afterResolve.set(result.content())
        );

        String raw = afterResolve.get();
        if (!StringUtils.hasText(raw)) {
            return null;
        }

        // 尝试解析 JSON，提取 narrative 字段
        try {
            String jsonStr = raw.trim();
            if (jsonStr.startsWith("```")) {
                int start = jsonStr.indexOf("\n");
                int end = jsonStr.lastIndexOf("```");
                if (start > 0 && end > start) {
                    jsonStr = jsonStr.substring(start + 1, end).trim();
                }
            }
            Map<String, String> parsed = objectMapper.readValue(jsonStr, new TypeReference<Map<String, String>>() {});
            String title = parsed.get("title");
            String narrative = parsed.get("narrative");

            StringBuilder result = new StringBuilder();
            if (StringUtils.hasText(title)) {
                result.append("**").append(title).append("**\n\n");
            }
            if (StringUtils.hasText(narrative)) {
                result.append(narrative);
            } else {
                // 若 AI 没返回 narrative 字段，直接使用原始文本
                result.append(raw);
            }
            return result.toString();
        } catch (Exception e) {
            // 解析失败则直接返回原始文本
            log.debug("解析 MissionAI 叙事 JSON 失败，直接使用原始文本: {}", e.getMessage());
            return raw;
        }
    }

    /**
     * 构建 system prompt：告诉 MissionAI 它是一个跑团叙事机器人
     */
    private String buildSystemPrompt() {
        return """
                你是一个跑团（TRPG）叙事机器人。你的任务是根据 D20 检定结果，为玩家的行动生成生动的叙事文字。\
                你的叙事必须让玩家强烈感受到成功的喜悦和失败的代价，尤其是在大成功和大失败时要有令人印象深刻的戏剧效果。

                ## 输出格式
                请严格按照以下 JSON 格式输出，不要包含 markdown 代码块标记或其他额外内容，例如好的、我明白了等。直接输出 JSON 字符串：

                {
                    "title": "检定标题（简短的概括，如「巧妙的开锁」「鲁莽的冲撞」「致命的失误」）",
                    "narrative": "叙事描述（3-6 句话，必须生动具体地描述过程和结果）"
                }

                ## 四种判定结果的叙事要求

                ### 🌟 大成功（自然 20）—— 超越极限
                自然 20 意味着命运之神的眷顾，角色不仅完美达成了目标，还获得了超出预期的额外收益：
                - **必须包含具体的奖励效果**：例如撬锁时发现了暗格中的财宝、交涉时意外获得了对方的信任和额外情报、战斗中击中要害造成双倍伤害或缴械对手、潜行时发现了捷径或敌人的弱点。
                - **叙事要有"传奇色彩"**：这不是普通的成功，而是令人惊叹的表现。旁观者可能会目瞪口呆，敌人可能会闻风丧胆。
                - **可以在叙事末尾建议 GM 给予额外奖励**：例如额外信息、临时增益、战利品、声望提升等。
                - **标题示例**：「命运的宠儿」「神之一击」「不可思议的奇迹」

                ### ✅ 成功 —— 如愿以偿
                角色达成了目标，过程可能顺利也可能稍有波折，但结果是好的：
                - **必须明确写出成功的具体表现**：门被打开了、守卫被说服了、机关被拆除了。不要含糊带过。
                - **可以加入正面收益**：不仅达成了目标，还可能附带一点小收获——比如速度比预期快、动作特别优雅、获得了有用的信息。
                - **可以有小波折但最终成功**：比如撬锁时发出了轻微的响声但无人察觉，攀爬时踩落了几块碎石但有惊无险。
                - **标题示例**：「稳健的手法」「巧妙的应对」「有惊无险」

                ### ❌ 失败 —— 功亏一篑
                角色未能达成目标，需要承担失败的后果：
                - **必须写出失败的具体惩罚**：门仍然锁着且撬锁工具折断了、守卫被激怒并提高了警戒、攀爬时滑落并受到了轻微伤害、交涉失败导致对方态度变得更差。
                - **失败要有代价**：不要只是"没能做到"，还要写出因此带来的负面后果——时间被浪费、资源被消耗、处境变得更危险、机会消失了。
                - **失败不一定是因为角色无能**：可能是外部因素（地面突然震动、一阵风暴露了行踪、对方恰好心情不好），让失败显得合理而非刻意贬低角色。
                - **标题示例**：「功亏一篑」「时机不对」「不幸的失误」

                ### 💀 大失败（自然 1）—— 灾难降临
                自然 1 是命运最残酷的玩笑，不仅彻底失败，还引发了更严重的连锁反应：
                - **必须写出灾难性的连锁后果**：撬锁时不仅工具断裂还触发了警报、潜行时不慎踢翻火盆引发火灾或暴露了全队位置、交涉时说出了极其冒犯的话语导致被驱逐或遭到攻击、战斗中武器脱手飞出或误伤友军。
                - **后果应该是持续的**：不是"摔了一跤爬起来就好"，而是留下了需要后续处理的问题——装备损坏需要修理、关系破裂需要弥补、受伤需要治疗、被通缉需要躲避。
                - **可以在叙事末尾暗示后续可能面临的麻烦**：让玩家感受到大失败的影响不会在当下就结束。
                - **标题示例**：「灾厄临头」「最坏的时机」「命运的嘲弄」

                ## 通用要求
                1. title 要简短有力，一眼看出结果性质，不要超过 10 个字
                2. narrative 要结合角色设定、事件背景和对话历史展开，让人感觉这是一个连贯的故事
                3. 叙事文字要有画面感和情绪张力，让玩家或惊喜、或紧张、或懊悔、或哭笑不得
                4. 根据 difficulty（DC）的高低调整叙事尺度：低难度失败更尴尬，高难度成功更值得骄傲
                5. 只输出 JSON，不要包含 ```json 等标记
                """;
    }

    /**
     * 构建 user prompt：完整上下文（角色设定 + 对话历史 + 检定信息）
     */
    private String buildUserPrompt(String characterSetting, String recentHistory,
                                   String event, int difficulty, int modifier, int roll, int finalResult,
                                   String rollDetail, String outcome) {
        StringBuilder sb = new StringBuilder();

        // 角色设定
        if (StringUtils.hasText(characterSetting)) {
            sb.append("## 角色设定\n\n").append(characterSetting).append("\n\n");
        }

        // 最近对话历史
        if (StringUtils.hasText(recentHistory)) {
            sb.append("## 最近对话记录\n\n").append(recentHistory).append("\n\n");
        }

        // 检定信息
        sb.append("## 检定信息\n\n");
        sb.append("- **事件**：").append(event).append("\n");
        sb.append("- **难度 DC**：").append(difficulty).append("\n");
        if (modifier != 0) {
            sb.append("- **属性补正**：").append(modifier > 0 ? "+" : "").append(modifier).append("\n");
        }
        sb.append("- **投掷详情**：").append(rollDetail).append("\n");
        sb.append("- **投掷结果**：").append(roll);
        if (modifier != 0) {
            sb.append("（最终结果：").append(roll).append(" + (").append(modifier > 0 ? "+" : "").append(modifier).append(") = ").append(finalResult).append("）");
        }
        sb.append("\n");
        sb.append("- **判定**：");
        switch (outcome) {
            case "critical_success" -> sb.append("🌟 大成功（自然 20）—— 请生成带有额外奖励和传奇色彩的叙事！");
            case "critical_failure" -> sb.append("💀 大失败（自然 1）—— 请生成带有灾难性连锁后果的叙事！");
            case "success" -> sb.append("✅ 成功 —— 请描述成功的具体表现和可能的正面收益");
            case "failure" -> sb.append("❌ 失败 —— 请描述失败的具体代价和负面后果");
        }

        return sb.toString();
    }

    @Data
    private static class Arguments {
        private Integer difficulty;
        private String event;
        private Integer modifier;
        private String advantage;
    }
}
