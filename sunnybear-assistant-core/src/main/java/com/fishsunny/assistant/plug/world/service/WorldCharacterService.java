package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观角色服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldCharacter;

import java.io.IOException;
import java.util.List;

public interface WorldCharacterService {

    WorldCharacter findById(String id);

    List<WorldCharacter> findByWorldId(String worldId);

    List<WorldCharacter> findAll();

    WorldCharacter save(WorldCharacter worldCharacter) throws IOException;

    /** 更新角色（按 id 定位，改名即改 name 字段） */
    WorldCharacter update(WorldCharacter worldCharacter) throws IOException;

    /** 删除角色并清理其知识关联 */
    WorldCharacter deleteById(String id);
}
