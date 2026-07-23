package com.fishsunny.assistant.plug.character.repository;

/*
 * @Usage 角色-会话映射数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class CharacterSessionMappingRepositoryImplement implements CharacterSessionMappingRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CharacterSessionMappingRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<CharacterSessionMapping> rowMapper = (resultSet, i) -> {
        CharacterSessionMapping mapping = new CharacterSessionMapping();
        mapping.setId(resultSet.getString("id"));
        mapping.setSessionId(resultSet.getString("session_id"));
        mapping.setCharacterId(resultSet.getString("character_id"));
        mapping.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        return mapping;
    };

    @Override
    public CharacterSessionMapping insert(CharacterSessionMapping mapping) {
        String sql =
                """
                INSERT INTO character_session_mapping
                (id, session_id, character_id, create_time)
                VALUES (?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                mapping.getId(),
                mapping.getSessionId(),
                mapping.getCharacterId(),
                mapping.getCreateTime().format(formatter)
        );
        return selectBySessionId(mapping.getSessionId());
    }

    @Override
    public CharacterSessionMapping deleteById(String id) {
        CharacterSessionMapping mapping = selectById(id);
        if (mapping == null) {
            return null;
        }
        String sql = "DELETE FROM character_session_mapping WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return mapping;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        String sql = "DELETE FROM character_session_mapping WHERE session_id = ?";
        jdbcTemplate.update(sql, sessionId);
    }

    @Override
    public void deleteByCharacterId(String characterId) {
        String sql = "DELETE FROM character_session_mapping WHERE character_id = ?";
        jdbcTemplate.update(sql, characterId);
    }

    @Override
    public CharacterSessionMapping selectBySessionId(String sessionId) {
        String sql = "SELECT * FROM character_session_mapping WHERE session_id = ?";
        List<CharacterSessionMapping> list = jdbcTemplate.query(sql, rowMapper, sessionId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public List<CharacterSessionMapping> selectByCharacterId(String characterId) {
        String sql = "SELECT * FROM character_session_mapping WHERE character_id = ? ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper, characterId);
    }

    @Override
    public List<CharacterSessionMapping> selectAll() {
        String sql = "SELECT * FROM character_session_mapping ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    private CharacterSessionMapping selectById(String id) {
        String sql = "SELECT * FROM character_session_mapping WHERE id = ?";
        List<CharacterSessionMapping> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }
}
