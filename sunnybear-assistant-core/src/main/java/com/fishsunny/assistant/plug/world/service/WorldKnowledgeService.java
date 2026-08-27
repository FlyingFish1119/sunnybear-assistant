package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观知识服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;

import java.util.List;

public interface WorldKnowledgeService {

    WorldKnowledge findById(String id);

    List<WorldKnowledge> findByWorldId(String worldId);

    WorldKnowledge save(WorldKnowledge knowledge);

    /** 更新知识（按 id 定位，知晓角色全量替换） */
    WorldKnowledge update(WorldKnowledge knowledge);

    WorldKnowledge deleteById(String id);
}
