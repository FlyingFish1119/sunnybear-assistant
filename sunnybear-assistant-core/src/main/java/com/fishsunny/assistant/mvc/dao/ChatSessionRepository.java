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

public interface ChatSessionRepository {

    public ChatSession insert(ChatSession chatSession);

    public ChatSession update(ChatSession chatSession);

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
