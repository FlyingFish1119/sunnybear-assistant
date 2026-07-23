package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色-会话映射服务接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;

import java.util.List;

public interface CharacterSessionMappingService {

    /** 创建映射 */
    CharacterSessionMapping createMapping(String sessionId, String characterId);

    /** 按会话 ID 查找映射 */
    CharacterSessionMapping findBySessionId(String sessionId);

    /** 按角色 ID 查找所有映射 */
    List<CharacterSessionMapping> findByCharacterId(String characterId);

    /** 删除会话映射 */
    void deleteBySessionId(String sessionId);

    /** 删除角色所有映射 */
    void deleteByCharacterId(String characterId);

    /** 根据角色 ID 获取所有关联的会话 ID */
    List<String> findSessionIdsByCharacterId(String characterId);
}
