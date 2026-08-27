package com.fishsunny.assistant.plug.character.repository;

/*
 * @Usage 角色词条数据访问接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;

import java.util.List;

public interface CharacterGlossaryRepository {

    CharacterGlossary insert(CharacterGlossary glossary);

    CharacterGlossary update(CharacterGlossary glossary);

    CharacterGlossary deleteById(Long id);

    CharacterGlossary selectById(Long id);

    List<CharacterGlossary> selectByCharacterId(String characterId);

    CharacterGlossary selectByCharacterIdAndKeyword(String characterId, String keyword);

    /** 按关键词/描述模糊搜索某个角色的词条 */
    List<CharacterGlossary> searchByCharacterId(String characterId, String searchText);

    /** 删除某个角色的全部词条（级联删除角色时使用） */
    void deleteByCharacterId(String characterId);
}
