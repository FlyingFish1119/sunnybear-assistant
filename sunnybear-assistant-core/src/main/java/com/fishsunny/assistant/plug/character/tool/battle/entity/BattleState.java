package com.fishsunny.assistant.plug.character.tool.battle.entity;

/*
 * @Usage 战斗状态实体 —— 对应 player_state / enemy_state 表
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BattleState {

    private String name;
    private String description;
    private Integer hp;
    private Integer mp;
    private Integer maxHp;
    private Integer maxMp;

    public BattleState() {
    }
}
