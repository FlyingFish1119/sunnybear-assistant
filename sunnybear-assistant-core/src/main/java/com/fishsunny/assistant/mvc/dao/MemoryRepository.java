package com.fishsunny.assistant.mvc.dao;

/*
 * @Usage 核心记忆数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import com.fishsunny.assistant.remote.RemoteRepository;

import java.util.List;

@RemoteRepository(repoCls = MemoryRepository.class)
public interface MemoryRepository {

    MemoryRecord insert(MemoryRecord record);

    MemoryRecord update(MemoryRecord record);

    MemoryRecord deleteById(Integer id);

    MemoryRecord selectById(Integer id);

    List<MemoryRecord> selectAll();

    /**
     * 分组重命名：把 oldName 组下所有记忆刷成 newName
     *
     * @return 受影响条数（原组名不存在返回 0）
     */
    int renameGroup(String oldName, String newName);
}
