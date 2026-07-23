package com.fishsunny.assistant.plug.character.dto;

/*
 * @Usage 战斗回合数据 —— 每回合通过 WebSocket 发给前端，前端据此渲染战斗 UI
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import com.fishsunny.assistant.plug.character.tool.battle.entity.BattleBuff;
import com.fishsunny.assistant.plug.character.tool.battle.entity.BattleSkill;
import com.fishsunny.assistant.plug.character.tool.battle.entity.BattleState;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class BattleTurnAsk {

    /** 回合编号（从 1 开始） */
    private int round;

    /** 战斗叙事消息列表（累积所有已结算的回合叙事，前端滚动展示） */
    private List<String> messages;

    /** 当前是否允许玩家选择技能（轮到玩家时为 true，GM 结算中为 false） */
    private boolean canAct;

    /** 玩家当前状态 */
    private BattleState player;

    private BattleState enemy;

    private List<BattleSkill> playerSkills;

    private List<BattleSkill> enemySkills;

    private List<BattleBuff> playerBuffs;

    private List<BattleBuff> enemyBuffs;
}
