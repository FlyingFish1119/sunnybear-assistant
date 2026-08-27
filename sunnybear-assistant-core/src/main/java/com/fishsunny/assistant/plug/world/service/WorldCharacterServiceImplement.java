package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界观角色服务实现（id 主键，世界内 name 唯一）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import com.fishsunny.assistant.plug.world.repository.WorldCharacterRepository;
import com.fishsunny.assistant.plug.world.repository.WorldKnowledgeRepository;
import com.fishsunny.assistant.utils.image.Database64ScaleImageHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class WorldCharacterServiceImplement implements WorldCharacterService {

    private static final Logger log = LoggerFactory.getLogger(WorldCharacterServiceImplement.class);

    private final WorldCharacterRepository worldCharacterRepository;
    private final WorldKnowledgeRepository worldKnowledgeRepository;

    @Autowired
    public WorldCharacterServiceImplement(WorldCharacterRepository worldCharacterRepository,
                                          WorldKnowledgeRepository worldKnowledgeRepository) {
        this.worldCharacterRepository = worldCharacterRepository;
        this.worldKnowledgeRepository = worldKnowledgeRepository;
    }

    @Override
    public WorldCharacter findById(String id) {
        return worldCharacterRepository.selectById(id);
    }

    @Override
    public List<WorldCharacter> findByWorldId(String worldId) {
        return worldCharacterRepository.selectByWorldId(worldId);
    }

    @Override
    public List<WorldCharacter> findAll() {
        return worldCharacterRepository.selectAll();
    }

    @Override
    public WorldCharacter save(WorldCharacter worldCharacter) throws IOException {
        if (!StringUtils.hasText(worldCharacter.getWorldId())) {
            throw new RuntimeException("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getName())) {
            throw new RuntimeException("角色名称不能为空");
        }
        checkNameUnique(worldCharacter.getWorldId(), worldCharacter.getName(), null);
        if (!StringUtils.hasText(worldCharacter.getId())) {
            worldCharacter.setId(UUID.randomUUID().toString());
        }
        worldCharacter.setCreateTime(LocalDateTime.now())
                .setUpdateTime(LocalDateTime.now());
        clearIllegal(worldCharacter);
        return worldCharacterRepository.insert(worldCharacter);
    }

    @Override
    @Transactional
    public WorldCharacter update(WorldCharacter worldCharacter) throws IOException {
        if (!StringUtils.hasText(worldCharacter.getId())) {
            throw new RuntimeException("角色 ID 不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getWorldId())) {
            throw new RuntimeException("世界观 ID 不能为空");
        }
        if (!StringUtils.hasText(worldCharacter.getName())) {
            throw new RuntimeException("角色名称不能为空");
        }
        if (worldCharacterRepository.selectById(worldCharacter.getId()) == null) {
            throw new RuntimeException("角色不存在");
        }
        checkNameUnique(worldCharacter.getWorldId(), worldCharacter.getName(), worldCharacter.getId());
        clearIllegal(worldCharacter);
        worldCharacter.setUpdateTime(LocalDateTime.now());
        return worldCharacterRepository.update(worldCharacter);
    }

    @Override
    @Transactional
    public WorldCharacter deleteById(String id) {
        // 先删角色，再清理该角色在知识中的关联
        WorldCharacter deleted = worldCharacterRepository.deleteById(id);
        worldKnowledgeRepository.deleteCharacterAssocByCharacterId(id);
        return deleted;
    }

    /** 校验同一世界观内角色名唯一（排除 excludeId） */
    private void checkNameUnique(String worldId, String name, String excludeId) {
        WorldCharacter existing = worldCharacterRepository.selectByWorldAndName(worldId, name);
        if (existing != null && (excludeId == null || !excludeId.equals(existing.getId()))) {
            throw new RuntimeException("该世界观下已存在同名角色: " + name);
        }
    }

    private void clearIllegal(WorldCharacter worldCharacter) throws IOException {
        if (!StringUtils.hasText(worldCharacter.getAvatar())) {
            worldCharacter.setAvatar("");
        } else {
            Database64ScaleImageHelper helper = new Database64ScaleImageHelper(worldCharacter.getAvatar());
            worldCharacter.setAvatar(helper.scaleImage(256));
        }
        if (!StringUtils.hasText(worldCharacter.getAiSettings())) {
            worldCharacter.setAiSettings("{}");
        }
        if (!StringUtils.hasText(worldCharacter.getSetting())) {
            worldCharacter.setSetting("");
        }
        if (!StringUtils.hasText(worldCharacter.getIntro())) {
            worldCharacter.setIntro("");
        }
    }
}
