package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage session-知识库映射数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fishsunny.assistant.engine.protocol.project.entity.SessionKnowledgeRecord;

public interface SessionKnowledgeRepository {

    SessionKnowledgeRecord upsertBySessionId(SessionKnowledgeRecord record);

    SessionKnowledgeRecord selectBySessionId(String sessionId);

    void deleteBySessionId(String sessionId);
}
