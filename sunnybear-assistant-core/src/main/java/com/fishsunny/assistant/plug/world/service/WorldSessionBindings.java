package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观会话绑定 —— 定义 chat_session 的 type 与 extension 键，并提供从会话解析世界观 ID 的静态工具
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/28
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;

import java.util.Map;

public class WorldSessionBindings {

    /** chat_session.type 中用于标识世界观会话的值 */
    public static final String SESSION_TYPE = "world";

    /** chat_session.extension 中存放世界观 ID 的键 */
    public static final String EXTENSION_KEY = "worldId";

    /**
     * 从会话的 extension 中解析绑定的世界观 ID。
     * <p>extension 为 JSON 对象（如 {"worldId":"xxx"}），未绑定返回 null。
     */
    public static String resolveWorldId(ChatSession session) {
        if (session == null) {
            return null;
        }
        Map<String, Object> extension = session.getExtension();
        if (extension == null) {
            return null;
        }
        Object value = extension.get(EXTENSION_KEY);
        return value == null ? null : String.valueOf(value);
    }
}
