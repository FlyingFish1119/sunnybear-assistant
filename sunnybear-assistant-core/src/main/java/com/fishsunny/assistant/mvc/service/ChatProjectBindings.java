package com.fishsunny.assistant.mvc.service;

/*
 * @Usage 项目会话绑定 —— 定义「项目会话」挂在哪种会话类型上、extension 里用哪个键存项目 ID，
 *        并提供从会话解析项目 ID 的静态工具（与 CharacterSessionBindings 同一套写法）。
 *
 *        与角色会话的关键差别：角色会话是独立 type（'character'，不进普通对话列表）；
 *        项目会话仍然是普通对话（'chat'），只是多挂一个项目指针 ——
 *        需求是「项目会话要出现在普通对话列表里，按项目分个组」，
 *        所以 type 绝不能被项目占用，否则它就从普通列表里消失了。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import org.springframework.util.StringUtils;

import java.util.Map;

public class ChatProjectBindings {

    /** 项目会话所用的会话类型：项目会话就是普通对话，不另开 type */
    public static final String SESSION_TYPE = "chat";

    /**
     * chat_session.extension 中存放项目 ID 的键。
     * <p>值必须是字符串：按 extension 反查会话走的是 json_extract，
     * 存成数字时取出来是整数，跟字符串参数比较会静默不相等。
     */
    public static final String EXTENSION_KEY = "chat_project_id";

    /**
     * 解绑时写入的值：空串。
     * <p>为何不用 null：mergeExtension 是整段 JSON 重新序列化，
     * 空串能保证键一定存在且值为空（resolveProjectId 判空即返回 null，按 extension 反查也不会命中），
     * 不受序列化时 null 是否被丢弃的配置影响。
     */
    public static final String UNBOUND = "";

    /**
     * 项目入口文档名：落在 {basePath}/project/{projectId}/ 下。
     * <p>新建项目时自动生成一份占位；{@code /init} 探索完也写这个名字（同名覆盖 = 刷新文档）。
     */
    public static final String PROJECT_FILE_NAME = "PROJECT.md";

    /**
     * 从会话的 extension 中解析绑定的项目 ID。
     * <p>extension 形如 {"chat_project_id":"xxx"}，未绑定返回 null。
     */
    public static String resolveProjectId(ChatSession session) {
        if (session == null) {
            return null;
        }
        Map<String, Object> extension = session.getExtension();
        if (extension == null) {
            return null;
        }
        Object value = extension.get(EXTENSION_KEY);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return StringUtils.hasText(text) ? text : null;
    }
}
