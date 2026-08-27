package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观知识服务实现（校验 + 知晓角色全量替换 + 级联清理）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import com.fishsunny.assistant.plug.world.repository.WorldCharacterRepository;
import com.fishsunny.assistant.plug.world.repository.WorldKnowledgeRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorldKnowledgeServiceImplement implements WorldKnowledgeService {

    private static final Logger log = LoggerFactory.getLogger(WorldKnowledgeServiceImplement.class);

    private final WorldKnowledgeRepository worldKnowledgeRepository;
    private final WorldCharacterRepository worldCharacterRepository;

    @Autowired
    public WorldKnowledgeServiceImplement(WorldKnowledgeRepository worldKnowledgeRepository,
                                          WorldCharacterRepository worldCharacterRepository) {
        this.worldKnowledgeRepository = worldKnowledgeRepository;
        this.worldCharacterRepository = worldCharacterRepository;
    }

    @Override
    public WorldKnowledge findById(String id) {
        WorldKnowledge knowledge = worldKnowledgeRepository.selectById(id);
        if (knowledge == null) {
            return null;
        }
        knowledge.setCharacterIds(worldKnowledgeRepository.selectCharacterIdsByKnowledgeId(id));
        return knowledge;
    }

    @Override
    public List<WorldKnowledge> findByWorldId(String worldId) {
        List<WorldKnowledge> list = worldKnowledgeRepository.selectByWorldId(worldId);
        // 一次查询全部关联，内存分组，避免 N+1
        Map<String, List<String>> assocMap = worldKnowledgeRepository.selectCharacterIdsMapByWorldId(worldId);
        for (WorldKnowledge knowledge : list) {
            knowledge.setCharacterIds(assocMap.getOrDefault(knowledge.getId(), new ArrayList<>()));
        }
        return list;
    }

    @Override
    @Transactional
    public WorldKnowledge save(WorldKnowledge knowledge) {
        if (!StringUtils.hasText(knowledge.getWorldId())) {
            throw new RuntimeException("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(knowledge.getTitle())) {
            throw new RuntimeException("知识标题不能为空");
        }
        if (!StringUtils.hasText(knowledge.getId())) {
            knowledge.setId(UUID.randomUUID().toString());
        }
        clearIllegal(knowledge);
        // 校验知晓角色都属于该世界观
        validateCharacterIds(knowledge.getWorldId(), knowledge.getCharacterIds());
        knowledge.setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        worldKnowledgeRepository.insert(knowledge);
        worldKnowledgeRepository.insertCharacterAssoc(knowledge.getId(), knowledge.getCharacterIds());
        return findById(knowledge.getId());
    }

    @Override
    @Transactional
    public WorldKnowledge update(WorldKnowledge knowledge) {
        if (!StringUtils.hasText(knowledge.getId())) {
            throw new RuntimeException("知识 ID 不能为空");
        }
        if (!StringUtils.hasText(knowledge.getWorldId())) {
            throw new RuntimeException("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(knowledge.getTitle())) {
            throw new RuntimeException("知识标题不能为空");
        }
        if (worldKnowledgeRepository.selectById(knowledge.getId()) == null) {
            throw new RuntimeException("知识不存在");
        }
        clearIllegal(knowledge);
        validateCharacterIds(knowledge.getWorldId(), knowledge.getCharacterIds());
        knowledge.setUpdateTime(LocalDateTime.now());
        worldKnowledgeRepository.update(knowledge);
        // 知晓角色全量替换：先删后插
        worldKnowledgeRepository.deleteCharacterAssocByKnowledgeId(knowledge.getId());
        worldKnowledgeRepository.insertCharacterAssoc(knowledge.getId(), knowledge.getCharacterIds());
        return findById(knowledge.getId());
    }

    @Override
    @Transactional
    public WorldKnowledge deleteById(String id) {
        worldKnowledgeRepository.deleteCharacterAssocByKnowledgeId(id);
        return worldKnowledgeRepository.deleteById(id);
    }

    /** 校验知晓角色都存在且属于同一世界观 */
    private void validateCharacterIds(String worldId, List<String> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return;
        }
        for (String characterId : characterIds) {
            WorldCharacter character = worldCharacterRepository.selectById(characterId);
            if (character == null) {
                throw new RuntimeException("关联角色不存在: " + characterId);
            }
            if (!worldId.equals(character.getWorldId())) {
                throw new RuntimeException("关联角色不属于该世界观: " + character.getName());
            }
        }
    }

    private void clearIllegal(WorldKnowledge knowledge) {
        if (!StringUtils.hasText(knowledge.getTitle())) {
            knowledge.setTitle("");
        }
        if (!StringUtils.hasText(knowledge.getContent())) {
            knowledge.setContent("");
        }
        if (knowledge.getCharacterIds() == null) {
            knowledge.setCharacterIds(new ArrayList<>());
        }
        // 去重，保持稳定顺序
        knowledge.setCharacterIds(knowledge.getCharacterIds().stream()
                .filter(StringUtils::hasText)
                .distinct()
                .collect(Collectors.toList()));
    }
}
