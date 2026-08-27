package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界观知识数据访问接口（主表 + 知识↔角色 关联表）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;

import java.util.List;
import java.util.Map;

public interface WorldKnowledgeRepository {

    WorldKnowledge insert(WorldKnowledge knowledge);

    WorldKnowledge update(WorldKnowledge knowledge);

    WorldKnowledge deleteById(String id);

    /** 删除某世界观下的全部知识（级联删除用） */
    void deleteByWorldId(String worldId);

    List<WorldKnowledge> selectByWorldId(String worldId);

    WorldKnowledge selectById(String id);

    List<WorldKnowledge> selectAll();

    // ==================== 知识↔角色 关联表 ====================

    /** 批量写入某条知识的知晓角色关联 */
    void insertCharacterAssoc(String knowledgeId, List<String> characterIds);

    /** 删除某条知识的全部关联（update 全量替换用） */
    void deleteCharacterAssocByKnowledgeId(String knowledgeId);

    /** 删除某角色的全部知识关联（角色删除联动用） */
    void deleteCharacterAssocByCharacterId(String characterId);

    /** 删除某世界观下全部知识的关联（世界观级联用） */
    void deleteCharacterAssocByWorldId(String worldId);

    /** 查询某条知识的知晓角色 id（按插入顺序） */
    List<String> selectCharacterIdsByKnowledgeId(String knowledgeId);

    /** 一次查询返回某世界观全部 知识→角色 id 关联（knowledgeId -> ids），避免 N+1 */
    Map<String, List<String>> selectCharacterIdsMapByWorldId(String worldId);
}
