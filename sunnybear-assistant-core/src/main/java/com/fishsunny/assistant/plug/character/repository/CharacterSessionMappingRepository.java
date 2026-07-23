package com.fishsunny.assistant.plug.character.repository;

/*
 * @Usage 角色-会话映射数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;

import java.util.List;

public interface CharacterSessionMappingRepository {

    CharacterSessionMapping insert(CharacterSessionMapping mapping);

    CharacterSessionMapping deleteById(String id);

    /** 按会话 ID 删除映射 */
    void deleteBySessionId(String sessionId);

    /** 按角色 ID 删除所有映射 */
    void deleteByCharacterId(String characterId);

    /** 按会话 ID 查询映射 */
    CharacterSessionMapping selectBySessionId(String sessionId);

    /** 按角色 ID 查询所有映射 */
    List<CharacterSessionMapping> selectByCharacterId(String characterId);

    /** 查询所有映射 */
    List<CharacterSessionMapping> selectAll();
}
