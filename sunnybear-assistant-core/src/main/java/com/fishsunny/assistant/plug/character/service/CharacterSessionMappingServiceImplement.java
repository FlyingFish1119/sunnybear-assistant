package com.fishsunny.assistant.plug.character.service;

/*
 * @Usage 角色-会话映射服务实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;
import com.fishsunny.assistant.plug.character.repository.CharacterSessionMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CharacterSessionMappingServiceImplement implements CharacterSessionMappingService {

    private final CharacterSessionMappingRepository mappingRepository;

    @Autowired
    public CharacterSessionMappingServiceImplement(CharacterSessionMappingRepository mappingRepository) {
        this.mappingRepository = mappingRepository;
    }

    @Override
    public CharacterSessionMapping createMapping(String sessionId, String characterId) {
        // 如果该会话已有映射，先删除旧映射
        CharacterSessionMapping existing = mappingRepository.selectBySessionId(sessionId);
        if (existing != null) {
            mappingRepository.deleteById(existing.getId());
        }

        CharacterSessionMapping mapping = new CharacterSessionMapping(sessionId, characterId);
        mapping.setId(UUID.randomUUID().toString())
                .setCreateTime(LocalDateTime.now());
        return mappingRepository.insert(mapping);
    }

    @Override
    public CharacterSessionMapping findBySessionId(String sessionId) {
        return mappingRepository.selectBySessionId(sessionId);
    }

    @Override
    public List<CharacterSessionMapping> findByCharacterId(String characterId) {
        return mappingRepository.selectByCharacterId(characterId);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        mappingRepository.deleteBySessionId(sessionId);
    }

    @Override
    public void deleteByCharacterId(String characterId) {
        mappingRepository.deleteByCharacterId(characterId);
    }

    @Override
    public List<String> findSessionIdsByCharacterId(String characterId) {
        List<CharacterSessionMapping> mappings = mappingRepository.selectByCharacterId(characterId);
        return mappings.stream()
                .map(CharacterSessionMapping::getSessionId)
                .collect(Collectors.toList());
    }
}
