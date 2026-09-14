package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 01:59
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;

import java.util.List;
import java.util.Map;

public interface ChatSessionRepository {

    public ChatSession insert(ChatSession chatSession);

    public ChatSession update(ChatSession chatSession);

    /**
     * 以 append-only 方式合并 extension：先读当前值，只覆盖传入的键，再整列写回。
     * <p>这是修改 extension 的唯一入口——不提供整段覆盖方法，避免调用方把别人的字段清掉。
     * 同时不动 name / update_time，不会把会话顶到列表最前。
     *
     * @param id     会话 id
     * @param fields 要写入的键值对（值可为对象/数组，会按 JSON 序列化）
     * @return 合并后的 extension JSON 字符串
     */
    public String mergeExtension(String id, Map<String, Object> fields);

    public ChatSession deleteById(String id);

    public List<ChatSession> selectAll();

    /** 按 type 筛选会话列表（如 'chat'、'cron'） */
    public List<ChatSession> selectByType(String type);

    /**
     * keyset 分页查询会话（按 update_time DESC, id DESC 稳定排序）。
     *
     * @param type       会话类型（'chat'、'cron'）
     * @param limit      最多返回条数
     * @param beforeTime 游标：上一页最旧一条的 update_time（yyyy-MM-dd HH:mm:ss）；null 表示取最新一页
     * @param beforeId   游标：上一页最旧一条的 id（与 beforeTime 配套，用作同秒内的稳定排序平局裁决）
     * @return 严格早于游标的会话（最多 limit 条）
     */
    public List<ChatSession> selectByTypePage(String type, int limit, String beforeTime, String beforeId);

    public ChatSession selectById(String id);

    /**
     * 按 type + extension 内的 JSON 字段值查询会话（json_extract）。
     * jsonKey/value 的语义由调用方（插件）约定，核心层不感知。
     */
    public List<ChatSession> selectByTypeAndExtensionValue(String type, String jsonKey, String value);
}
