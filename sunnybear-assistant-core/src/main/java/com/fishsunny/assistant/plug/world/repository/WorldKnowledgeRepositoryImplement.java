package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界观知识数据访问实现（JdbcTemplate + RowMapper）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldKnowledge;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
public class WorldKnowledgeRepositoryImplement implements WorldKnowledgeRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WorldKnowledgeRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<WorldKnowledge> rowMapper = (resultSet, i) -> {
        WorldKnowledge knowledge = new WorldKnowledge();
        knowledge.setId(resultSet.getString("id"));
        knowledge.setWorldId(resultSet.getString("world_id"));
        knowledge.setTitle(resultSet.getString("title"));
        knowledge.setContent(resultSet.getString("content"));
        knowledge.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        knowledge.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return knowledge;
    };

    @Override
    public WorldKnowledge insert(WorldKnowledge knowledge) {
        String sql =
                """
                INSERT INTO world_knowledge
                (id, world_id, title, content, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                knowledge.getId(),
                knowledge.getWorldId(),
                knowledge.getTitle() != null ? knowledge.getTitle() : "",
                knowledge.getContent() != null ? knowledge.getContent() : "",
                knowledge.getCreateTime().format(formatter),
                knowledge.getUpdateTime().format(formatter)
        );
        return selectById(knowledge.getId());
    }

    @Override
    public WorldKnowledge update(WorldKnowledge knowledge) {
        String sql =
                """
                UPDATE world_knowledge
                SET title = ?, content = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                knowledge.getTitle() != null ? knowledge.getTitle() : "",
                knowledge.getContent() != null ? knowledge.getContent() : "",
                knowledge.getUpdateTime().format(formatter),
                knowledge.getId()
        );
        return selectById(knowledge.getId());
    }

    @Override
    public WorldKnowledge deleteById(String id) {
        WorldKnowledge knowledge = selectById(id);
        if (knowledge == null) {
            throw new RuntimeException("知识不存在");
        }
        String sql = "DELETE FROM world_knowledge WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return knowledge;
    }

    @Override
    public void deleteByWorldId(String worldId) {
        String sql = "DELETE FROM world_knowledge WHERE world_id = ?";
        jdbcTemplate.update(sql, worldId);
    }

    @Override
    public List<WorldKnowledge> selectByWorldId(String worldId) {
        String sql = "SELECT * FROM world_knowledge WHERE world_id = ? ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper, worldId);
    }

    @Override
    public WorldKnowledge selectById(String id) {
        String sql = "SELECT * FROM world_knowledge WHERE id = ?";
        List<WorldKnowledge> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public List<WorldKnowledge> selectAll() {
        String sql = "SELECT * FROM world_knowledge ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    // ==================== 知识↔角色 关联表 ====================

    @Override
    public void insertCharacterAssoc(String knowledgeId, List<String> characterIds) {
        if (characterIds == null || characterIds.isEmpty()) {
            return;
        }
        List<Object[]> batchArgs = characterIds.stream()
                .map(id -> new Object[]{knowledgeId, id})
                .collect(Collectors.toList());
        jdbcTemplate.batchUpdate(
                "INSERT INTO world_knowledge_character (knowledge_id, character_id) VALUES (?, ?)",
                batchArgs);
    }

    @Override
    public void deleteCharacterAssocByKnowledgeId(String knowledgeId) {
        jdbcTemplate.update("DELETE FROM world_knowledge_character WHERE knowledge_id = ?", knowledgeId);
    }

    @Override
    public void deleteCharacterAssocByCharacterId(String characterId) {
        jdbcTemplate.update("DELETE FROM world_knowledge_character WHERE character_id = ?", characterId);
    }

    @Override
    public void deleteCharacterAssocByWorldId(String worldId) {
        jdbcTemplate.update(
                "DELETE FROM world_knowledge_character WHERE knowledge_id IN (SELECT id FROM world_knowledge WHERE world_id = ?)",
                worldId);
    }

    @Override
    public List<String> selectCharacterIdsByKnowledgeId(String knowledgeId) {
        String sql = "SELECT character_id FROM world_knowledge_character WHERE knowledge_id = ? ORDER BY rowid";
        return jdbcTemplate.query(sql, (rs, i) -> rs.getString("character_id"), knowledgeId);
    }

    @Override
    public Map<String, List<String>> selectCharacterIdsMapByWorldId(String worldId) {
        String sql =
                "SELECT knowledge_id, character_id FROM world_knowledge_character " +
                "WHERE knowledge_id IN (SELECT id FROM world_knowledge WHERE world_id = ?) ORDER BY rowid";
        return jdbcTemplate.query(sql, (ResultSet rs) -> {
            Map<String, List<String>> map = new LinkedHashMap<>();
            while (rs.next()) {
                String knowledgeId = rs.getString("knowledge_id");
                String characterId = rs.getString("character_id");
                map.computeIfAbsent(knowledgeId, k -> new ArrayList<>()).add(characterId);
            }
            return map;
        }, worldId);
    }
}
