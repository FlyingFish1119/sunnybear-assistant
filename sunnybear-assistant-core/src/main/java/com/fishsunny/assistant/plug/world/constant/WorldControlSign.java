package com.fishsunny.assistant.plug.world.constant;

/**
 * world 插件专属的控制信号。
 * <p>
 * 插件特有的控制信号不放进全局 {@code ControlSign}，避免插件常量泄漏到主工程，
 * 统一收拢在本类供插件内部（群聊服务 / 前端契约）使用。
 */
public class WorldControlSign {

    /** 群聊轮次边界，应携带 "sessionId|角色名" 字符串 */
    public static final String WORLD_ROUND = "###WORLD_ROUND###";

    /** 调度器选中夺舍角色、回合交还玩家时通知前端，应携带 "sessionId|角色名" 字符串 */
    public static final String WORLD_POSSESS = "###WORLD_POSSESS###";
}
