package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色词条服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;

import java.util.List;

public interface CharacterGlossaryService {

    List<CharacterGlossary> listByCharacterId(String characterId);

    CharacterGlossary getByCharacterIdAndKeyword(String characterId, String keyword);

    CharacterGlossary create(CharacterGlossary glossary);

    CharacterGlossary update(CharacterGlossary glossary);

    void deleteById(Long id);
}
