package com.fishsunny.assistant.plug.character.tool.battle.entity;

/*
 * @Usage 战斗技能实体 —— 对应 player_skills / enemy_skills 表
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class BattleSkill {

    private Integer id;
    private String name;
    private String description;
    private Integer cost;
    private String damageDice;
    private String effect;
    private Integer difficulty;

    public BattleSkill() {
    }
}
