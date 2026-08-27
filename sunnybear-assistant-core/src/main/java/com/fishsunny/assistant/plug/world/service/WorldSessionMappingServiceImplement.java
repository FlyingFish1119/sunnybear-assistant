package com.fishsunny.assistant.plug.world.service;

/*
 * @Usage 世界-会话映射服务实现
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;
import com.fishsunny.assistant.plug.world.repository.WorldSessionMappingRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class WorldSessionMappingServiceImplement implements WorldSessionMappingService {

    private final WorldSessionMappingRepository mappingRepository;

    @Autowired
    public WorldSessionMappingServiceImplement(WorldSessionMappingRepository mappingRepository) {
        this.mappingRepository = mappingRepository;
    }

    @Override
    public WorldSessionMapping createMapping(String sessionId, String worldId) {
        // 如果该会话已有映射，先删除旧映射
        WorldSessionMapping existing = mappingRepository.selectBySessionId(sessionId);
        if (existing != null) {
            mappingRepository.deleteById(existing.getId());
        }

        WorldSessionMapping mapping = new WorldSessionMapping(sessionId, worldId);
        mapping.setId(UUID.randomUUID().toString())
                .setCreateTime(LocalDateTime.now());
        return mappingRepository.insert(mapping);
    }

    @Override
    public WorldSessionMapping findBySessionId(String sessionId) {
        return mappingRepository.selectBySessionId(sessionId);
    }

    @Override
    public List<WorldSessionMapping> findByWorldId(String worldId) {
        return mappingRepository.selectByWorldId(worldId);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        mappingRepository.deleteBySessionId(sessionId);
    }

    @Override
    public void deleteByWorldId(String worldId) {
        mappingRepository.deleteByWorldId(worldId);
    }

    @Override
    public List<String> findSessionIdsByWorldId(String worldId) {
        List<WorldSessionMapping> mappings = mappingRepository.selectByWorldId(worldId);
        return mappings.stream()
                .map(WorldSessionMapping::getSessionId)
                .collect(Collectors.toList());
    }
}
