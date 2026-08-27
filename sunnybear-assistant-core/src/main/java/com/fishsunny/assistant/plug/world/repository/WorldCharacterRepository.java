package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界观角色数据访问接口（id 主键，世界内 name 唯一）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldCharacter;

import java.util.List;

public interface WorldCharacterRepository {

    WorldCharacter insert(WorldCharacter worldCharacter);

    WorldCharacter update(WorldCharacter worldCharacter);

    WorldCharacter deleteById(String id);

    /** 删除某世界观下的全部角色（级联删除用） */
    void deleteByWorldId(String worldId);

    List<WorldCharacter> selectByWorldId(String worldId);

    WorldCharacter selectById(String id);

    /** 按世界 + 名字查询（name 唯一性校验用） */
    WorldCharacter selectByWorldAndName(String worldId, String name);

    List<WorldCharacter> selectAll();
}
