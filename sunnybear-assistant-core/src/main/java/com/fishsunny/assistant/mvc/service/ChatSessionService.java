package com.fishsunny.assistant.mvc.service;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 02:13
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;

import java.util.List;

public interface ChatSessionService {

    public ChatSession findById(String id);

    public List<ChatSession> findAll();

    /** 按 type 筛选会话列表 */
    public List<ChatSession> findByType(String type);

    public ChatSession save(ChatSession chatSession);

    public ChatSession update(ChatSession chatSession);

    public ChatSession deleteById(String id);
}
