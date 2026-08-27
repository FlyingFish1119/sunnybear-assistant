package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界-会话映射服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;

import java.util.List;

public interface WorldSessionMappingService {

    /** 创建映射（同一会话已有映射时先删除旧映射） */
    WorldSessionMapping createMapping(String sessionId, String worldId);

    /** 按会话 ID 查找映射 */
    WorldSessionMapping findBySessionId(String sessionId);

    /** 按世界观 ID 查找所有映射 */
    List<WorldSessionMapping> findByWorldId(String worldId);

    /** 删除会话映射 */
    void deleteBySessionId(String sessionId);

    /** 删除世界观所有映射 */
    void deleteByWorldId(String worldId);

    /** 根据世界观 ID 获取所有关联的会话 ID */
    List<String> findSessionIdsByWorldId(String worldId);
}
