package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 02:33
 */

import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;

import java.util.List;

public interface ChatMessageRepository {

    public ChatMessage insert(ChatMessage chatMessage);

    public ChatMessage update(ChatMessage chatMessage);

    public ChatMessage deleteById(String id);

    public int deleteBySessionId(String sessionId);

    public ChatMessage selectById(String id);

    public List<ChatMessage> selectBySessionId(String sessionId);

    /**
     * 查询兄弟消息（同一 parentId 下的所有消息）。
     * 当 parentId 为 null 时，查询同 session 下所有根消息。
     */
    public List<ChatMessage> selectSiblingsByParentId(String parentId, String sessionId);

    public int updateActive(String id, boolean active);

    public int batchUpdateActive(List<String> ids, boolean active);

    public void deleteByIds(List<String> ids);
}
