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

    public ChatSession selectById(String id);
}
