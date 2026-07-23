package com.fishsunny.assistant.plug.character.dto;

/*
 * @Usage 前端回传的玩家战斗行动 —— 玩家在战斗 UI 中选择的技能
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BattleTurnAction {

    // player /  enemy
    private String who;

    /** 会话 ID，用于路由回等待中的战斗工具 */
    private String sessionId;

    /** 玩家选择的技能索引（对应 BattleTurnAsk.SkillOption.index）。special 非空时忽略此字段 */
    private int skillIndex;

    public static final String SPECIAL_SURRENDER = "surrender";
    public static final String SPECIAL_FLEE = "flee";

    /**
     * 特殊行动类型，非空时 skillIndex 无效：
     * "surrender" — 直接放弃战斗（失败）
     * "flee"      — 尝试逃跑（需 D20 检定）
     * null        — 普通技能行动
     */
    private String special;
}
