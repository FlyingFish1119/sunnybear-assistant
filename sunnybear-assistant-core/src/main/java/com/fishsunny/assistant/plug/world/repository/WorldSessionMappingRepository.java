package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界-会话映射数据访问接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;

import java.util.List;

public interface WorldSessionMappingRepository {

    WorldSessionMapping insert(WorldSessionMapping mapping);

    WorldSessionMapping deleteById(String id);

    /** 按会话 ID 删除映射 */
    void deleteBySessionId(String sessionId);

    /** 按世界观 ID 删除所有映射 */
    void deleteByWorldId(String worldId);

    /** 按会话 ID 查询映射 */
    WorldSessionMapping selectBySessionId(String sessionId);

    /** 按世界观 ID 查询所有映射 */
    List<WorldSessionMapping> selectByWorldId(String worldId);

    /** 查询所有映射 */
    List<WorldSessionMapping> selectAll();
}
