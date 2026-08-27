package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色词条服务接口
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.plug.character.dto.GlossaryImportResult;
import com.fishsunny.assistant.plug.character.entity.CharacterGlossary;

import java.util.List;

public interface CharacterGlossaryService {

    List<CharacterGlossary> listByCharacterId(String characterId);

    /** 按关键词/描述模糊搜索词条，searchText 为空时返回全部 */
    List<CharacterGlossary> searchByCharacterId(String characterId, String searchText);

    CharacterGlossary getById(Long id);

    CharacterGlossary getByCharacterIdAndKeyword(String characterId, String keyword);

    CharacterGlossary create(CharacterGlossary glossary);

    CharacterGlossary update(CharacterGlossary glossary);

    void deleteById(Long id);

    /** 批量导入词条，关键词重复的条目覆盖更新，返回导入统计 */
    GlossaryImportResult importByCharacterId(String characterId, List<CharacterGlossary> items);
}
