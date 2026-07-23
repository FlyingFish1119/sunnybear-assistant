package com.fishsunny.assistant.plug.character.tool.battle.entity;

/*
 * @Usage 战斗 Buff 实体 —— 对应 player_buffs / enemy_buffs 表
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BattleBuff {

    private Integer id;
    private String name;
    private String description;

    /** 剩余回合数：-1 = 永久（不自动过期），0 = 本回合结束即清除，>0 = 剩余回合数 */
    private Integer remainingTurns;

    public BattleBuff() {
    }
}
