package com.fishsunny.assistant.plug.character.constant;

/*
 * @Usage 战斗引擎专用信号常量 —— 不污染核心 ControlSign，保持在 character plug 内部
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

public class BattleControlSign {

    /** 工具 → 前端：发送回合战斗数据，携带 BattleTurnAsk JSON */
    public static final String SIGN_BATTLE_TURN = "###BATTLE_TURN###";

    /** 工具 → 前端：战斗结束，携带结局叙事文本 */
    public static final String SIGN_BATTLE_END = "###BATTLE_END###";

    private BattleControlSign() {}
}
