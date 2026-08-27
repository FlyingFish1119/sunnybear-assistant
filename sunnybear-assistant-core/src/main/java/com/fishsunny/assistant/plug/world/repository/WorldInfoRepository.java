package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界观数据访问接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldInfo;

import java.util.List;

public interface WorldInfoRepository {

    WorldInfo insert(WorldInfo worldInfo);

    WorldInfo update(WorldInfo worldInfo);

    WorldInfo deleteById(String id);

    List<WorldInfo> selectAll();

    WorldInfo selectById(String id);

    /** 仅更新背景图字段（不触发 update() 中保留旧值的逻辑） */
    void updateBackground(String id, String background);
}
