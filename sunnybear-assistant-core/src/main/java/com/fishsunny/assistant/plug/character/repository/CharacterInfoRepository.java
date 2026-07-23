package com.fishsunny.assistant.plug.character.repository;

/*
 * @Usage 角色信息数据访问接口
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterInfo;

import java.util.List;

public interface CharacterInfoRepository {

    CharacterInfo insert(CharacterInfo characterInfo);

    CharacterInfo update(CharacterInfo characterInfo);

    CharacterInfo deleteById(String id);

    List<CharacterInfo> selectAll();

    CharacterInfo selectById(String id);

    /** 仅更新背景图字段（不触发 update() 中保留旧值的逻辑） */
    void updateBackground(String id, String background);
}
