package com.fishsunny.assistant.plug.character.tool.battle;

/*
 * @Usage 战斗引擎 —— 将主 Agent 传入的自然语言战斗描述，通过子 Agent（MissionAI）转换为结构化的战斗数据卡。
 *        角色所有的具体数值（HP/MP）、技能、Buff 全部由自然语言承载，MissionAI 负责提取并结构化。
 *        生成的卡牌存入临时 SQLite 数据库（每 session 一个独立 db 文件），战斗期间直接 UPDATE，
 *        战斗结束后删除文件。战斗是"当下的"，只保留结果，细节随风而逝。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15 10:36
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.ChatHttpHandler;
import com.fishsunny.assistant.engine.protocol.project.ChatRequest;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.engine.protocol.project.processor.ToolCallLoop;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.calc.CalculateTool;
import com.fishsunny.assistant.plug.character.constant.BattleControlSign;
import com.fishsunny.assistant.plug.character.controller.BattleController;
import com.fishsunny.assistant.plug.character.db.BattleDbManager;
import com.fishsunny.assistant.plug.character.dto.BattleTurnAction;
import com.fishsunny.assistant.plug.character.dto.BattleTurnAsk;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import com.fishsunny.assistant.plug.character.tool.battle.entity.BattleBuff;
import com.fishsunny.assistant.plug.character.tool.battle.entity.BattleSkill;
import com.fishsunny.assistant.plug.character.tool.battle.entity.BattleState;
import com.fishsunny.assistant.plug.character.tool.dice.D20Tool;
import com.fishsunny.assistant.plug.character.tool.dice.NDMTool;
import com.fishsunny.assistant.settings.AISettings;
import lombok.Data;
import lombok.experimental.Accessors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.sql.DataSource;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

@ToolKitComponent(BattleToolKit.class)
@ConditionalOnExpression("${plug.character.tool.battle.enable:false} && ${plug.character.tool.battle.battle-engine.enable:true}")
public class BattleEngineTool implements ToolHandler {

    public static final String NAME = "battle_engine_tool";

    private static final Logger log = LoggerFactory.getLogger(BattleEngineTool.class);

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final AISettings missionAISettings;
    private final ChatHttpHandler chatHttpHandler;
    private final ToolExecutor toolExecutor;
    private final ToolCallLoop toolCallLoop;
    private final BattleDbManager dbManager;

    public BattleEngineTool(ObjectMapper objectMapper,
                            @Qualifier(AISettings.MISSION) AISettings missionAISettings,
                            ChatHttpHandler chatHttpHandler,
                            @Lazy ToolExecutor executor,
                            ToolCallLoop toolCallLoop,
                            BattleDbManager dbManager
                            ) {
        this.objectMapper = objectMapper;
        this.missionAISettings = missionAISettings;
        this.chatHttpHandler = chatHttpHandler;
        this.dbManager = dbManager;
        this.toolExecutor = executor;
        this.toolCallLoop = toolCallLoop;

        // Step 1: 注册工具定义 —— 只收两段自然语言，所有数值/技能/Buff 都由 MissionAI 从中提取
        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        将自然语言描述的角色和敌人信息转换为结构化战斗数据，准备回合制战斗。""")
                .setRequired(List.of("playerDescription", "enemyDescription"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("playerDescription", "string",
                                "玩家角色的完整描述，含角色名、HP/MP、技能（伤害公式和效果）及初始 Buff"),
                        new ToolRegister.Parameters("enemyDescription", "string",
                                "敌人的完整描述，格式与玩家相同")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        CharacterInfo character = (CharacterInfo) context.get("character");

        // Step 1: 解析入参
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("战斗引擎参数解析错误: " + e.getMessage());
        }
        if (!StringUtils.hasText(arguments.getPlayerDescription())) {
            throw new ToolExecutor.ToolExecuteException("玩家角色描述（playerDescription）不能为空");
        }
        if (!StringUtils.hasText(arguments.getEnemyDescription())) {
            throw new ToolExecutor.ToolExecuteException("敌人描述（enemyDescription）不能为空");
        }

        // Step 2: 从上下文获取 sessionId 和 WebSocket 会话
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        String sessionId = chatSession != null ? chatSession.getId() : null;
        WebSocketSession wsSession = null;
        if (context.get("session") instanceof WebSocketSession s) {
            wsSession = s;
        }
        if (sessionId == null || wsSession == null) {
            throw new ToolExecutor.ToolExecuteException("战斗引擎需要有效的 session 和 WebSocket 连接");
        }

        // Step 2: 调用 MissionAI 生成结构化卡牌
        BattleCardResult cardResult;
        try {
            cardResult = generateBattleCards(
                    arguments.getPlayerDescription().trim(),
                    arguments.getEnemyDescription().trim());
        } catch (Exception e) {
            log.error("MissionAI 生成战斗卡牌失败", e);
            throw new ToolExecutor.ToolExecuteException("战斗卡牌生成失败: " + e.getMessage());
        }

        // Step 3: 创建临时 SQLite 数据库 → 建表 → 插入初始数据
        DataSource ds = dbManager.create(character.getId(), sessionId);
        // 将 DataSource 注入上下文，供 BattleSqlQueryTool / BattleSqlExecuteTool 等子工具使用
        context.put("battleDataSource", ds);
        try {
            dbManager.initTables(ds);
            insertBattleCards(ds, cardResult);
        } catch (Exception e) {
            context.remove("battleDataSource");
            dbManager.destroy(character.getId(), sessionId);
            throw new ToolExecutor.ToolExecuteException("初始化战斗数据库失败: " + e.getMessage());
        }

        // Step 4: 战斗循环 —— 薄层编排
        String battleResult;
        try {
            battleResult = runBattleLoop(sessionId, ds, wsSession, context);
        } catch (Exception e) {
            log.error("战斗过程异常 sessionId={}: {}", sessionId, e.getMessage(), e);
            battleResult = "⚔️ 战斗过程发生异常: " + e.getMessage();
        } finally {
            BattleController.cleanupAction(sessionId);
            dbManager.destroy(character.getId(), sessionId);
            log.info("战斗已结束，数据库已清理: sessionId={}", sessionId);
        }

        return new ToolExecutor.ToolExecuteResponse(NAME, battleResult);
    }

    @Override
    public String name() { return NAME; }

    @Override
    public ToolRegister getRegister() { return register; }

    // ==================== 卡牌生成（MissionAI） ====================

    private BattleCardResult generateBattleCards(String playerDesc, String enemyDesc) throws Exception {
        String systemPrompt = """
                你是一个 TRPG 战斗数据生成器。你的任务是根据玩家和敌人的自然语言描述，生成结构化的战斗数据卡。\
                你需要根据描述合理推断，为双方设定 HP、MP、技能和增益效果。

                ## 输出格式
                请严格按照以下 JSON 格式输出，不要包含 markdown 代码块标记或其他额外内容：

                {
                  "player": {
                    "name": "角色名称",
                    "description": "简短战斗描述",
                    "hp": 100,
                    "mp": 50,
                    "skills": [
                      {
                        "name": "技能名称",
                        "description": "技能描述",
                        "cost": 10,
                        "damageDice": "m × n + k",
                        "effect": "额外效果描述（如灼烧、击退、治疗等）",
                        "difficulty": 12
                      }
                    ],
                    "buffs": [
                      {
                        "name": "Buff 名称",
                        "description": "效果描述",
                        "remainingTurns": 3
                      }
                    ]
                  },
                  "enemy": {
                    与 player 结构完全相同
                  }
                }

                ## 数值设定参考

                ### HP/MP 范围
                - 弱小（村民、小动物）：HP 10-30, MP 0-10
                - 普通（士兵、野兽）：HP 30-80, MP 0-20
                - 精英（骑士、魔兽）：HP 80-150, MP 20-50
                - Boss（巨龙、魔王）：HP 150-400, MP 50-150

                ### 伤害骰格式（damageDice = "m × n + k"）
                m = 骰子数量（正整数），n = 骰子面数（正整数），k = 固定加值（非负整数）
                - 轻攻击：1 × 4 + 0、1 × 6 + 0
                - 中攻击：2 × 6 + 0、1 × 10 + 2、1 × 12 + 0
                - 重攻击：3 × 6 + 0、2 × 8 + 2、1 × 20 + 0
                - 必杀技：4 × 8 + 4、3 × 10 + 5

                ### 技能消耗（cost，MP）
                - 普通攻击：0-5
                - 中等技能：5-15
                - 强力技能：15-30
                - 必杀技：30-50

                ### 技能难度（difficulty，DC）
                - 自动命中填 0 或省略
                - 普通技能：8-12
                - 高难度技能：13-18

                ### 技能效果（effect）
                描述除伤害外的额外效果，如「灼烧 2 回合」「击退」「回复 2 × 6 HP」。无效果可为空字符串。

                ## 生成规则
                1. 所有信息（HP/MP/技能/伤害骰/消耗/效果/难度/Buff）都从自然语言描述中提取，不要凭空编造
                2. 如描述中明确给出了数值（如「HP 100」「伤害 3×6+0」），直接使用；未明确的部分则根据角色定位合理推断
                3. 如果描述中提到了技能名但未给出完整数据，根据技能名称和角色定位推断合理的伤害骰、消耗和难度
                4. 如果描述完全没有提及技能，根据角色特征自动生成 2-4 个合适的技能
                5. 数值要平衡：双方总战斗力应该大致相当，让战斗有悬念
                6. **Buff 持续回合**：remainingTurns 字段表示剩余回合数。-1 = 永久效果（不自动过期），0 = 本回合结束即清除，1-5 = 持续 N 回合。\
                   根据 Buff 描述合理推断持续时间（如「3 回合」则填 3），未明确说明持续时间的填 -1（默认永久）。
                7. 只输出纯 JSON，不要包含 ```json 等代码块标记，不要包含任何解释、问候或确认文字
                """;
        String userPrompt = """
                ## 玩家角色描述 ${playerDesc}
                ## 敌人描述 ${enemyDesc}
                请从以上自然语言描述中提取所有数值、技能和 Buff，生成双方的战斗数据卡。
                """.replace("${playerDesc}", playerDesc).replace("${enemyDesc}", enemyDesc);

        ChatRequest request = new ChatRequest()
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(userPrompt)
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

        String raw = afterResolve.get();
        if (!StringUtils.hasText(raw)) throw new Exception("MissionAI 返回为空");

        String jsonStr = raw.trim();
        if (jsonStr.startsWith("```")) {
            int start = jsonStr.indexOf("\n");
            int end = jsonStr.lastIndexOf("```");
            if (start > 0 && end > start) jsonStr = jsonStr.substring(start + 1, end).trim();
        }
        try {
            return objectMapper.readValue(jsonStr, BattleCardResult.class);
        } catch (Exception e) {
            log.error("解析战斗卡牌 JSON 失败，原始响应: {}", raw, e);
            throw new Exception("解析战斗卡牌 JSON 失败: " + e.getMessage());
        }
    }

    // ==================== 数据库写入（初始化） ====================

    /** 将生成的卡牌插入 6 张表中 */
    private void insertBattleCards(DataSource ds, BattleCardResult cards) throws Exception {
        try (Connection conn = ds.getConnection()) {
            insertState(conn, "player_state", cards.getPlayer());
            insertSkills(conn, "player_skills", cards.getPlayer() != null ? cards.getPlayer().getSkills() : null);
            insertBuffs(conn, "player_buffs", cards.getPlayer() != null ? cards.getPlayer().getBuffs() : null);
            insertState(conn, "enemy_state", cards.getEnemy());
            insertSkills(conn, "enemy_skills", cards.getEnemy() != null ? cards.getEnemy().getSkills() : null);
            insertBuffs(conn, "enemy_buffs", cards.getEnemy() != null ? cards.getEnemy().getBuffs() : null);
        }
    }

    private void insertState(Connection conn, String table, StateCard card) throws Exception {
        if (card == null) return;
        String sql = "INSERT INTO " + table + " (name, description, hp, mp, max_hp, max_mp) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            int hp = card.getHp() != null ? card.getHp() : 0;
            int mp = card.getMp() != null ? card.getMp() : 0;
            ps.setString(1, StringUtils.hasText(card.getName()) ? card.getName() : "未知");
            ps.setString(2, StringUtils.hasText(card.getDescription()) ? card.getDescription() : "");
            ps.setInt(3, hp);
            ps.setInt(4, mp);
            ps.setInt(5, hp); // maxHp = initial HP
            ps.setInt(6, mp); // maxMp = initial MP
            ps.executeUpdate();
        }
    }

    private void insertSkills(Connection conn, String table, List<SkillCard> skills) throws Exception {
        if (skills == null || skills.isEmpty()) return;
        String sql = "INSERT INTO " + table + " (id, name, description, cost, damage_dice, effect, difficulty) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < skills.size(); i++) {
                SkillCard s = skills.get(i);
                ps.setInt(1, i);
                ps.setString(2, defaultIfEmpty(s.getName(), "未命名"));
                ps.setString(3, defaultIfEmpty(s.getDescription(), ""));
                ps.setInt(4, s.getCost() != null ? s.getCost() : 0);
                ps.setString(5, defaultIfEmpty(s.getDamageDice(), ""));
                ps.setString(6, defaultIfEmpty(s.getEffect(), ""));
                ps.setInt(7, s.getDifficulty() != null ? s.getDifficulty() : 0);
                ps.executeUpdate();
            }
        }
    }

    private void insertBuffs(Connection conn, String table, List<BuffCard> buffs) throws Exception {
        if (buffs == null || buffs.isEmpty()) return;
        String sql = "INSERT INTO " + table + " (id, name, description, remaining_turns) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < buffs.size(); i++) {
                BuffCard b = buffs.get(i);
                ps.setInt(1, i);
                ps.setString(2, defaultIfEmpty(b.getName(), "未命名"));
                ps.setString(3, defaultIfEmpty(b.getDescription(), ""));
                ps.setInt(4, b.getRemainingTurns() != null ? b.getRemainingTurns() : -1);
                ps.executeUpdate();
            }
        }
    }

    // ==================== 战斗循环 ====================

    private String runBattleLoop(String sessionId, DataSource ds, WebSocketSession wsSession, Map<String, Object> context) throws Exception {
        int round = 0;
        List<String> roundMessages = new ArrayList<>();
        String status;

        while (true) {
            round++;

            // 1. 读取当前战斗状态 → 构建 BattleTurnAsk → 推送前端（轮到玩家，可操作）
            BattleState playerState = queryState(ds, "player_state");
            BattleState enemyState = queryState(ds, "enemy_state");
            pushTurnUpdate(wsSession, round, roundMessages, true, playerState, enemyState, ds);
            log.debug("战斗回合已推送（轮到玩家）: sessionId={}, round={}", sessionId, round);

            // 2. 等待玩家行动
            BattleTurnAction action = BattleController.awaitBattleAction(sessionId);
            if (action == null) {
                throw new Exception("等待玩家行动超时");
            }
            action.setWho(playerState.getName());

            // 3. 处理特殊行动
            if (BattleTurnAction.SPECIAL_SURRENDER.equals(action.getSpecial())) {
                status = BattleTurnAction.SPECIAL_SURRENDER;
                roundMessages.add("第" + round + "回合 — " + playerState.getName() + " 选择了放弃战斗……");
                pushTurnUpdate(wsSession, round, roundMessages, false,
                        playerState, enemyState, ds);
                break;
            }
            if (BattleTurnAction.SPECIAL_FLEE.equals(action.getSpecial())) {
                String fleeNarrative = processFlee(action, context);
                boolean escaped = fleeNarrative.startsWith("SUCCESS:");
                roundMessages.add("第" + round + "回合 — " + playerState.getName()
                        + (escaped ? " 成功逃离了战场！" : " 试图逃跑……\n") + fleeNarrative);
                if (escaped) {
                    status = BattleTurnAction.SPECIAL_FLEE;
                    pushTurnUpdate(wsSession, round, roundMessages, false,
                            playerState, enemyState, ds);
                    break;
                }
                // 逃跑失败：继续执行（跳过普通技能结算，直接进入敌人回合）
            }

            // 4. 玩家回合结算（普通技能，非特殊行动时执行）
            if (!StringUtils.hasText(action.getSpecial())) {
                String playerNarrative = processTurn(action, context);
                roundMessages.add("第" + round + "回合 — " + playerState.getName() + "：\n" + playerNarrative);
            }

            // 玩家行动后检查敌人是否死亡 → 死了就直接结束，敌人不再行动
            enemyState = queryState(ds, "enemy_state");
            if (enemyState.getHp() <= 0) {
                status = "win";
                // 结算后推送最新状态（不可操作）
                pushTurnUpdate(wsSession, round, roundMessages, false,
                        queryState(ds, "player_state"), enemyState, ds);
                break;
            }

            // 玩家回合结算后推送更新（不可操作，等待敌人回合）
            pushTurnUpdate(wsSession, round, roundMessages, false,
                    queryState(ds, "player_state"), enemyState, ds);

            // 4. 敌人 AI 决策
            String lastNarrative = roundMessages.isEmpty() ? null : roundMessages.get(roundMessages.size() - 1);
            BattleTurnAction enemyAction = enemyAction(ds, lastNarrative, context);

            // 5. 敌人回合结算
            String enemyNarrative = processTurn(enemyAction, context);
            roundMessages.add("第" + round + "回合 — " + enemyState.getName() + "：\n" + enemyNarrative);

            // 6. 检查玩家是否死亡
            BattleState player = queryState(ds, "player_state");
            if (player.getHp() <= 0) {
                status = "lose";
                // 结算后推送最新状态（不可操作）
                pushTurnUpdate(wsSession, round, roundMessages, false,
                        player, queryState(ds, "enemy_state"), ds);
                break;
            }

            // 敌人回合结算后推送更新（不可操作，等待下一回合）
            pushTurnUpdate(wsSession, round, roundMessages, false,
                    player, queryState(ds, "enemy_state"), ds);
        }

        // 生成收尾叙事并推送前端（基于完整战斗历史）
        String endingNarrative = generateEndingNarrative(status, ds, roundMessages);
        try {
            wsSession.sendMessage(new TextMessage(BattleControlSign.SIGN_BATTLE_END + endingNarrative));
        } catch (Exception e) {
            log.warn("推送战斗结束信号失败: sessionId={}", sessionId, e);
        }

        return endingNarrative;
    }

    /** 推送回合更新到前端 */
    private void pushTurnUpdate(WebSocketSession wsSession, int round, List<String> messages,
                                 boolean canAct, BattleState player, BattleState enemy,
                                 DataSource ds) throws Exception {
        BattleTurnAsk turnAsk = new BattleTurnAsk()
                .setRound(round)
                .setMessages(new ArrayList<>(messages))  // 拷贝一份避免并发
                .setCanAct(canAct)
                .setPlayer(player)
                .setEnemy(enemy)
                .setPlayerSkills(querySkill(ds, "player_skills"))
                .setEnemySkills(querySkill(ds, "enemy_skills"))
                .setPlayerBuffs(queryBuff(ds, "player_buffs"))
                .setEnemyBuffs(queryBuff(ds, "enemy_buffs"));
        String askJson = objectMapper.writeValueAsString(turnAsk);
        wsSession.sendMessage(new TextMessage(BattleControlSign.SIGN_BATTLE_TURN + askJson));
    }

    private BattleTurnAction enemyAction(DataSource ds, String lastRoundNarrative, Map<String, Object> context) throws Exception {
        BattleState player = queryState(ds, "player_state");
        BattleState enemy = queryState(ds, "enemy_state");
        List<BattleSkill> playerSkills = querySkill(ds, "player_skills");
        List<BattleSkill> enemySkills = querySkill(ds, "enemy_skills");
        List<BattleBuff> playerBuffs = queryBuff(ds, "player_buffs");
        List<BattleBuff> enemyBuffs = queryBuff(ds, "enemy_buffs");

        // 敌人 AI 提示词
        String systemPrompt = """
                你是一个 TRPG 战斗 AI，控制敌方角色进行回合制战斗。\
                你的任务是根据当前战场局势，从可用技能列表中选择最优的一项。

                ## 输出格式
                严格输出以下 JSON，不要包含 markdown 代码块标记，不要包含任何解释或问候语：
                {"skillIndex": 0}

                其中 skillIndex 是你选择的技能在技能列表中的序号（从 0 开始）。

                ## 决策原则（按优先级排序）
                1. **MP 足够**：必须确保所选技能的 cost <= 当前 MP，否则选择普通攻击（cost=0 的技能）
                2. **生死关头**：如果自身 HP 低于 30%，优先考虑有回复/防御/减伤效果的技能（effect 中包含回复、治疗、\
                   格挡、减伤、防御等关键词）
                3. **乘胜追击**：如果目标 HP 低于 25%，使用当前 MP 允许的最高伤害技能，争取一击必杀
                4. **普通情况**：综合考虑伤害期望和 MP 效率，选择性价比最高的技能。\
                   伤害期望 = 骰子数量 × (骰子面数 + 1) / 2 + 固定加值
                5. **节省 MP**：如果 MP 较低（低于 30%），优先使用 cost 低的技能或普通攻击
                6. **技能效果加分**：带有灼烧、中毒、流血、眩晕、击退、缴械等控制/持续效果的技能优先考虑

                ## 技能伤害骰格式
                damageDice 格式为 "m × n + k"，表示投 m 个 n 面骰子加 k 点固定值。\
                伤害期望 = m × (n + 1) / 2 + k。difficulty = 0 表示自动命中，无需检定。""";

        // 当前信息
        StringBuilder userPrompt = new StringBuilder();
        userPrompt.append("## 当前战场状态\n\n");

        userPrompt.append("### 敌方（你控制的角色）\n");
        userPrompt.append("- 名称：").append(enemy.getName()).append("\n");
        userPrompt.append("- HP：").append(enemy.getHp()).append("/").append(enemy.getMaxHp()).append("\n");
        userPrompt.append("- MP：").append(enemy.getMp()).append("/").append(enemy.getMaxMp()).append("\n");
        if (!enemyBuffs.isEmpty()) {
            userPrompt.append("- Buff/Debuff：");
            for (BattleBuff b : enemyBuffs) {
                userPrompt.append(b.getName());
                if (b.getDescription() != null && !b.getDescription().isEmpty()) {
                    userPrompt.append("（").append(b.getDescription()).append("）");
                }
                userPrompt.append("  ");
            }
            userPrompt.append("\n");
        }

        userPrompt.append("\n### 玩家\n");
        userPrompt.append("- 名称：").append(player.getName()).append("\n");
        userPrompt.append("- HP：").append(player.getHp()).append("/").append(player.getMaxHp()).append("\n");
        userPrompt.append("- MP：").append(player.getMp()).append("/").append(player.getMaxMp()).append("\n");
        if (!playerBuffs.isEmpty()) {
            userPrompt.append("- Buff/Debuff：");
            for (BattleBuff b : playerBuffs) {
                userPrompt.append(b.getName());
                if (b.getDescription() != null && !b.getDescription().isEmpty()) {
                    userPrompt.append("（").append(b.getDescription()).append("）");
                }
                userPrompt.append("  ");
            }
            userPrompt.append("\n");
        }

        userPrompt.append("\n### 你的可用技能\n");
        if (enemySkills.isEmpty()) {
            userPrompt.append("（无可用技能，返回 skillIndex: -1）\n");
        } else {
            for (int i = 0; i < enemySkills.size(); i++) {
                BattleSkill s = enemySkills.get(i);
                userPrompt.append(i).append(". **").append(s.getName()).append("**");
                userPrompt.append(" —— 消耗 ").append(s.getCost()).append(" MP");
                if (s.getDamageDice() != null && !s.getDamageDice().isEmpty()) {
                    userPrompt.append("，伤害 ").append(s.getDamageDice());
                }
                if (s.getDifficulty() != null && s.getDifficulty() > 0) {
                    userPrompt.append("，DC ").append(s.getDifficulty());
                }
                if (s.getEffect() != null && !s.getEffect().isEmpty()) {
                    userPrompt.append("，效果：").append(s.getEffect());
                }
                userPrompt.append("\n");
            }
        }

        if (lastRoundNarrative != null && !lastRoundNarrative.isEmpty()) {
            userPrompt.append("\n### 上一回合战况\n").append(lastRoundNarrative).append("\n");
        }

        userPrompt.append("\n请根据上述战场状态和决策原则，选择最优技能索引。只返回 JSON：{\"skillIndex\": N}");

        // 敌人行动 - JSON 模式
        ChatRequest request = new ChatRequest()
                .loadSettings(new AISettings().copy(missionAISettings).setResponseFormat("json_object"))
                .setMessages(List.of(new ChatMessage().system(systemPrompt), new ChatMessage().user(userPrompt.toString())));
        AtomicReference<String> content = new AtomicReference<>();
        chatHttpHandler.translate(UUID.randomUUID().toString(), missionAISettings.getAdapterName(), request, missionAISettings.getStream(), null,
                ((result, lastRes) -> content.set(result.content()))
                );
        BattleTurnAction battleTurnAction = new BattleTurnAction();

        // 解析结果
        try {
            battleTurnAction = objectMapper.readValue(content.get(), BattleTurnAction.class);
        } catch (Exception e) {
            battleTurnAction.setSkillIndex(-1);
        }
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        battleTurnAction.setSessionId(chatSession.getId());
        battleTurnAction.setWho(enemy.getName());

        return battleTurnAction;
    }

    // ==================== 逃跑检定 ====================

    /**
     * 处理玩家逃跑：调用 MissionAI 进行 D20 检定。
     * @return 以 "SUCCESS:" 开头的字符串表示逃跑成功，否则为失败叙事
     */
    private String processFlee(BattleTurnAction action, Map<String, Object> context) throws Exception {
        String systemPrompt = """
                你是一个 TRPG 战斗裁判（GM）。玩家试图从战斗中逃跑。

                ## 可用工具
                - **battle_db_query**：查询战斗数据库
                - **d20_check**：D20 检定

                ## 流程
                1. 查询 enemy_state 获取敌人信息
                2. 根据敌人强度设定逃跑 DC（普通敌人 DC 12，精英 DC 15，Boss DC 18）
                3. 调用 d20_check（event="逃跑检定"，difficulty=设定的DC）
                4. 返回结果，格式必须严格为：
                   - 成功 → `SUCCESS: <叙事>` （以 SUCCESS: 开头）
                   - 失败 → 纯文本叙事（描述逃跑失败的狼狈场景）

                ## 叙事要求
                1-2 句话描述逃跑过程。成功则写脱逃的惊险，失败则写被拦下的窘迫。\
                不要 JSON，不要 markdown 代码块，不要"好的""明白了"等前缀。""";

        String userPrompt = """
                ## 逃跑行动
                - 行动者：%s
                - 敌人：请从 enemy_state 查询

                请执行逃跑检定并返回结果。""".formatted(action.getWho());

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage().system(systemPrompt));
        messages.add(new ChatMessage().user(userPrompt));

        Set<String> includes = Set.of(BattleSqlQueryTool.NAME, D20Tool.NAME);
        List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegisterByHandlers(toolExecutor, includes);

        ChatRequest request = new ChatRequest()
                .loadSettings(missionAISettings)
                .setMessages(messages)
                .setTools(toolRegisters);

        AtomicReference<String> result = new AtomicReference<>("");
        result.set(toolCallLoop.execute(missionAISettings, request, context));

        String text = result.get();
        return text != null ? text.trim() : "逃跑失败（检定异常）";
    }

    // ==================== 战斗结局叙事 ====================

    /**
     * 调用 MissionAI 生成战斗结局叙事。不依赖工具调用，直接根据最终 DB 状态生成收尾文字。
     */
    private String generateEndingNarrative(String outcome, DataSource ds, List<String> roundMessages) throws Exception {
        BattleState player = queryState(ds, "player_state");
        BattleState enemy = queryState(ds, "enemy_state");

        boolean win = "win".equals(outcome);
        String winnerName = win ? player.getName() : enemy.getName();
        String loserName = win ? enemy.getName() : player.getName();
        String winnerHp = win ? (player.getHp() + "/" + player.getMaxHp()) : (enemy.getHp() + "/" + enemy.getMaxHp());

        // 构建战斗历史摘要
        StringBuilder historyBuilder = new StringBuilder();
        if (roundMessages != null && !roundMessages.isEmpty()) {
            historyBuilder.append("## 战斗全过程\n");
            for (String msg : roundMessages) {
                historyBuilder.append("- ").append(msg.replace("\n", " ")).append("\n");
            }
        }

        String systemPrompt = """
                你是一个 TRPG 战斗叙事机器人。战斗已经结束，请根据战斗全过程记录生成一段简短有力的结局叙事。\
                要概括战斗的关键转折和精彩瞬间，让结局更有故事感。

                ## 输出格式
                严格输出纯文本，不要 JSON，不要 markdown 代码块，不要"好的""明白了"等前缀。3-5 句话即可。""";

        String userPrompt = """
                ## 战斗结果
                - 胜利方：%s
                - 落败方：%s
                - 胜利方剩余 HP：%s

                %s

                请根据以上战斗全过程，生成一段 TRPG 风格的战斗结局叙事。如果胜利方是玩家角色，语气要昂扬、有成就感；\
                如果胜利方是敌人，语气要沉重、但不失尊严，可以给玩家留一个复仇的伏笔。\
                叙事应该概括战斗的关键转折，让这场战斗感觉像一个完整的故事章节的结束。"""
                .formatted(winnerName, loserName, winnerHp, historyBuilder.toString());

        ChatRequest request = new ChatRequest()
                .setMessages(List.of(
                        new ChatMessage().system(systemPrompt),
                        new ChatMessage().user(userPrompt)
                ))
                .loadSettings(missionAISettings);

        try {
            AtomicReference<String> afterResolve = new AtomicReference<>("");
            chatHttpHandler.translate(
                    UUID.randomUUID().toString(),
                    missionAISettings.getAdapterName(),
                    request,
                    false,
                    null,
                    (result, lastRes) -> afterResolve.set(result.content())
            );
            String narrative = afterResolve.get();
            if (StringUtils.hasText(narrative)) {
                return narrative.trim();
            }
        } catch (Exception e) {
            log.warn("生成战斗结局叙事失败，使用降级文本: {}", e.getMessage());
        }

        // 降级：直接返回简单文本
        if (win) {
            return "⚔️ 战斗结束！**" + player.getName() + "** 击败了 " + enemy.getName() + "，"
                    + "剩余 HP " + player.getHp() + "/" + player.getMaxHp() + "！";
        } else {
            return "💀 战斗结束……**" + player.getName() + "** 被 " + enemy.getName() + " 击败了……";
        }
    }

    /**
     * 处理回合：将选择的技能 + 当前战斗状态交给 GM（MissionAI），
     * GM 通过 BattleSqlQueryTool 读取数据库、BattleSqlExecuteTool 更新数据库，
     * 最终返回本回合的战斗叙事文本。
     */
    private String processTurn(BattleTurnAction action, Map<String, Object> context) throws Exception {
        String systemPrompt = """
                你是一个 TRPG 战斗裁判（GM）。你的职责是：接收行动者及其使用的技能索引，查询数据库获取完整状态，\
                投掷伤害骰子、判定命中（如有难度 DC）、更新战斗数据库、并生成生动的回合叙事。

                ## 可用工具
                - **battle_db_query**：查询战斗数据库（SELECT / PRAGMA）。6 张初始表：
                  player_state / player_skills / player_buffs / enemy_state / enemy_skills / enemy_buffs
                - **battle_db_execute**：写入战斗数据库（INSERT / UPDATE / DELETE）
                - **ndm_roll**：投掷伤害骰子（n 个 m 面骰 + 固定加值），参数 count/sides/bonus，可选 reason
                - **d20_check**：D20 技能/属性检定，参数 difficulty/event/modifier/advantage，返回大成功/成功/失败/大失败

                ## 核心原则：批量并行，减少轮次
                每一轮你可以同时调用多个工具，系统会并行执行它们。**关键：能在同一轮做的事，绝对不要拆成多轮。**\
                你要力争在 2-3 轮工具调用内完成整个回合结算。

                ---

                ## 第一轮：一次性查清全场状态
                **同时调用 6 个 battle_db_query**，每条查一张表，一次拿到全部信息：

                1. `SELECT * FROM player_state`
                2. `SELECT * FROM enemy_state`
                3. `SELECT * FROM player_skills`
                4. `SELECT * FROM enemy_skills`
                5. `SELECT * FROM player_buffs`
                6. `SELECT * FROM enemy_buffs`

                拿到结果后，完成以下判断（无需额外查询）：
                - **确定身份**：行动者名字匹配 player_state.name 还是 enemy_state.name？匹配方 = 行动者方，\
                  另一方 = 目标方。如 `{行动者}` 匹配 player，则目标表为 enemy；反之目标表为 player。
                - **定位技能**：在行动者方的 skills 表中找到 id = skillIndex({技能索引}) 的技能。
                - **MP 检查**：如果行动者当前 MP < 技能 cost，改用 cost=0 的普通攻击技能。\
                  若找不到 cost=0 的技能则返回叙事「MP 不足且无可用技能」并终止。
                - **Buff 分析 → 优劣势判定**（重要！）：检查双方的 buffs 表，根据 buff 名称和描述判断 D20 检定时\
                  是否有优势或劣势。判断规则：
                  - 行动者方 buff 含「精准」「锁定」「弱点洞察」「必中」「祝福」「鼓舞」等增强命中/伤害的词 → **advantage**
                  - 行动者方 buff 含「盲目」「虚弱」「诅咒」「眩晕」「混乱」「迟缓」等削弱攻击的词 → **disadvantage**
                  - 目标方 buff 含「闪避」「隐身」「潜行」「恍惚」「灵巧」「偏斜」「石肤」等增强闪避/防御的词 → **disadvantage**（对攻击者而言）
                  - 目标方 buff 含「倒地」「麻痹」「束缚」「震荡」「破甲」「燃烧」等削弱闪避/防御的词 → **advantage**（对攻击者而言）
                  - 优/劣势同时存在则互相抵消 → 普通检定（不填 advantage 参数）
                  - 根据 buff 描述还可给出 modifier 建议（+2~+5 或 -2~-5），不强制

                - **Buff 回合更新**（重要！）：在第一轮查询结果后，必须对**行动者方**的 buffs 表执行回合更新：
                  1. **递减回合数**：对行动者方 buffs 表中 `remaining_turns > 0` 的所有 buff，将其 remaining_turns 减 1，\
                     UPDATE 写回数据库。`remaining_turns = -1` 的永久 buff 不处理。
                  2. **清除过期 buff**：DELETE 行动者方 buffs 表中 `remaining_turns = 0` 的记录。
                  3. **豁免检定**：检查行动者方 buffs 表中是否有需要豁免检定的负面效果（description 中包含「豁免」「save」「DC」\
                     或效果为中毒、眩晕、麻痹、诅咒、魅惑、恐惧等异常状态）。对每个此类 buff：
                     - 从 description 中提取 DC（如「DC 12」则 difficulty=12，未注明则默认 10）
                     - 调用 d20_check 进行豁免检定（event 填「{buff名称} 豁免」）
                     - **豁免成功（success/critical_success）**：DELETE 该 buff
                     - **豁免失败（failure/critical_failure）**：保留该 buff，继续生效
                  4. 此更新**只对行动者方的 buffs 表执行**（目标方的 buff 在其自己的回合更新）。
                  5. 更新完成后，重新查询行动者方 buffs，以获取最新状态进行后续判定。

                ---

                ## 第二轮：乐观并行投掷 —— D20 和伤害骰同时出手！
                **关键：不管命不命中，伤害骰子先投了再说。命中失败再弃掉伤害结果即可。**\
                这样只需一轮就能完成全部掷骰，不需要等 D20 结果出来再投伤害。

                ### 如果技能无 DC（difficulty = 0，自动命中）：

                ---

                ## 第三轮：根据 D20 结果更新数据库 + 生成叙事

                ### 命中判定结果处理：
                - **大成功（critical_success / nat20）**：伤害翻倍（ndm_roll 结果 × 2），可附加额外效果（击退、缴械等）
                - **成功（success）**：正常应用 ndm_roll 的伤害值
                - **失败（failure）**：伤害作废！不扣目标 HP，但仍扣除行动者 MP（技能消耗了）
                - **大失败（critical_failure）**：伤害作废，扣除 MP，并追加负面效果（自伤 1d4、武器脱手、\
                  下回合劣势等），用 INSERT 写入行动者方 buffs 表

                ### 数据库更新（用 battle_db_execute）：
                ```sql
                -- 命中时更新目标 HP 和行动者 MP
                UPDATE {目标表}_state SET hp = MAX(0, hp - {最终伤害});
                UPDATE {行动者表}_state SET mp = MAX(0, mp - {技能cost});

                -- 未命中时只扣 MP
                UPDATE {行动者表}_state SET mp = MAX(0, mp - {技能cost});

                -- 技能 effect 非空时，将效果作为新 buff 写入目标 buffs 表（必须指定 remaining_turns）
                INSERT INTO {目标表}_buffs (id, name, description, remaining_turns)
                VALUES ((SELECT COALESCE(MAX(id), -1) + 1 FROM {目标表}_buffs), '{效果名称}', '{效果描述}', {持续回合数});

                -- 大失败自伤
                UPDATE {行动者表}_state SET hp = MAX(0, hp - {自伤值});
                ```

                ### 叙事要求（3-5 句话，纯文本）：
                - 首句：行动者使用技能的动作描写
                - 次句：D20 检定过程（如有 DC）—— 投出 X，优/劣势如何，命中/未命中
                - 三句：伤害描述和 buff 效果 —— 命中则写伤害值 + 画面感，未命中则写擦身而过/被格挡/被闪避
                - 结尾：双方剩余状态（HP/MP）+ 新 buff 提示
                - 目标 HP ≤ 0 时写出击倒/击杀的终结感
                - **直接返回纯文本叙事，不要 JSON，不要 markdown 代码块，不要加"好的""明白了"等前缀**
                """;

        String userPrompt = """
                ## 本回合行动
                - 行动者：%s
                - 使用的技能索引（skillIndex）：%d

                请严格按照系统提示的**三轮流程**执行结算：
                1. 第一轮：同时查 6 张表，分析 Buff 决定优/劣势
                2. 第二轮：同时投 D20（如有 DC）和伤害骰子（乐观并行）
                3. 第三轮：根据 D20 结果更新数据库 + 生成叙事

                如果行动者 MP 不足或技能索引无效，改用 cost=0 的普通攻击。\
                第三轮结束后直接返回纯文本叙事。"""
                .formatted(action.getWho(), action.getSkillIndex());

        Set<String> includes = Set.of(BattleSqlExecuteTool.NAME, BattleSqlQueryTool.NAME, D20Tool.NAME, NDMTool.NAME, CalculateTool.NAME);
        List<StandardToolRegister> toolRegisters = StandardToolRegister.buildToolRegisterByHandlers(toolExecutor, includes);

        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage().system(systemPrompt));
        messages.add(new ChatMessage().user(userPrompt));

        ChatRequest request = new ChatRequest()
                .loadSettings(missionAISettings)
                .setMessages(messages)
                .setTools(toolRegisters);

        AtomicReference<String> result = new AtomicReference<>("");
        result.set(toolCallLoop.execute(missionAISettings, request, context));

        return result.get();
    }

    // ==================== SQL 工具方法 ====================
    private static final RowMapper<BattleState> stateRowMapper = (rs, rowNum) ->
            new BattleState()
                .setName(rs.getString("name"))
                .setDescription(rs.getString("description"))
                .setHp(rs.getInt("hp"))
                .setMp(rs.getInt("mp"))
                .setMaxHp(rs.getInt("max_hp"))
                .setMaxMp(rs.getInt("max_mp"));

    private BattleState queryState(DataSource dataSource, String table) throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return jdbcTemplate.queryForObject("SELECT * FROM " + table, stateRowMapper);
    }

    private static final RowMapper<BattleSkill> skillRowMapper = (rs, rowNum) -> new BattleSkill()
        .setId(rs.getInt("id"))
        .setName(rs.getString("name"))
        .setDescription(rs.getString("description"))
        .setCost(rs.getInt("cost"))
        .setDamageDice(rs.getString("damage_dice"))
        .setEffect(rs.getString("effect"))
        .setDifficulty(rs.getInt("difficulty"));

    private List<BattleSkill> querySkill(DataSource dataSource, String table) throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return jdbcTemplate.query("SELECT * FROM " + table, skillRowMapper);
    }

    private static final RowMapper<BattleBuff> buffRowMapper = (rs, rowNum) ->
            new BattleBuff()
                .setId(rs.getInt("id"))
                .setName(rs.getString("name"))
                .setDescription(rs.getString("description"))
                .setRemainingTurns(rs.getInt("remaining_turns"));

    private List<BattleBuff> queryBuff(DataSource dataSource, String table) throws Exception {
        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        return jdbcTemplate.query("SELECT * FROM " + table, buffRowMapper);
    }

    // ==================== 工具方法 ====================

    private static String defaultIfEmpty(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    // ==================== 数据类 ====================

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String playerDescription;
        private String enemyDescription;
    }

    @Data
    @Accessors(chain = true)
    private static class BattleCardResult {
        private StateCard player;
        private StateCard enemy;
    }

    @Data
    @Accessors(chain = true)
    private static class StateCard {
        private String name;
        private String description;
        private Integer hp;
        private Integer mp;
        private List<SkillCard> skills;
        private List<BuffCard> buffs;
    }

    @Data
    @Accessors(chain = true)
    private static class SkillCard {
        private String name;
        private String description;
        private Integer cost;
        private String damageDice;
        private String effect;
        private Integer difficulty;
    }

    @Data
    @Accessors(chain = true)
    private static class BuffCard {
        private String name;
        private String description;
        /** 剩余回合数：-1 = 永久，0 = 即时清除，>0 = 持续 N 回合 */
        private Integer remainingTurns;
    }

    /** 技能行 —— 从 enemy_skills 表中读取的简化结构 */
    private record SkillRow(int id, String name, int cost, String damageDice, String effect, int difficulty) {}
}
