package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 核心记忆数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;

import java.util.List;

public interface MemoryRepository {

    MemoryRecord insert(MemoryRecord record);

    MemoryRecord update(MemoryRecord record);

    MemoryRecord deleteById(Integer id);

    MemoryRecord selectById(Integer id);

    List<MemoryRecord> selectAll();
}
