package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界-会话映射数据访问实现
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldSessionMapping;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class WorldSessionMappingRepositoryImplement implements WorldSessionMappingRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WorldSessionMappingRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<WorldSessionMapping> rowMapper = (resultSet, i) -> {
        WorldSessionMapping mapping = new WorldSessionMapping();
        mapping.setId(resultSet.getString("id"));
        mapping.setSessionId(resultSet.getString("session_id"));
        mapping.setWorldId(resultSet.getString("world_id"));
        mapping.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        return mapping;
    };

    @Override
    public WorldSessionMapping insert(WorldSessionMapping mapping) {
        String sql =
                """
                INSERT INTO world_session_mapping
                (id, session_id, world_id, create_time)
                VALUES (?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                mapping.getId(),
                mapping.getSessionId(),
                mapping.getWorldId(),
                mapping.getCreateTime().format(formatter)
        );
        return selectBySessionId(mapping.getSessionId());
    }

    @Override
    public WorldSessionMapping deleteById(String id) {
        WorldSessionMapping mapping = selectById(id);
        if (mapping == null) {
            return null;
        }
        String sql = "DELETE FROM world_session_mapping WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return mapping;
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        String sql = "DELETE FROM world_session_mapping WHERE session_id = ?";
        jdbcTemplate.update(sql, sessionId);
    }

    @Override
    public void deleteByWorldId(String worldId) {
        String sql = "DELETE FROM world_session_mapping WHERE world_id = ?";
        jdbcTemplate.update(sql, worldId);
    }

    @Override
    public WorldSessionMapping selectBySessionId(String sessionId) {
        String sql = "SELECT * FROM world_session_mapping WHERE session_id = ?";
        List<WorldSessionMapping> list = jdbcTemplate.query(sql, rowMapper, sessionId);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public List<WorldSessionMapping> selectByWorldId(String worldId) {
        String sql = "SELECT * FROM world_session_mapping WHERE world_id = ? ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper, worldId);
    }

    @Override
    public List<WorldSessionMapping> selectAll() {
        String sql = "SELECT * FROM world_session_mapping ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    private WorldSessionMapping selectById(String id) {
        String sql = "SELECT * FROM world_session_mapping WHERE id = ?";
        List<WorldSessionMapping> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }
}
