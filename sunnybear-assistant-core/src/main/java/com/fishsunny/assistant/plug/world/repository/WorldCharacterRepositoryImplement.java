package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界观角色数据访问实现（id 主键，世界内 name 唯一）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldCharacter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class WorldCharacterRepositoryImplement implements WorldCharacterRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WorldCharacterRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<WorldCharacter> rowMapper = (resultSet, i) -> {
        WorldCharacter worldCharacter = new WorldCharacter();
        worldCharacter.setId(resultSet.getString("id"));
        worldCharacter.setWorldId(resultSet.getString("world_id"));
        worldCharacter.setName(resultSet.getString("name"));
        worldCharacter.setAiSettings(resultSet.getString("ai_settings"));
        worldCharacter.setSetting(resultSet.getString("setting"));
        worldCharacter.setIntro(resultSet.getString("intro"));
        worldCharacter.setAvatar(resultSet.getString("avatar"));
        worldCharacter.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        worldCharacter.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return worldCharacter;
    };

    @Override
    public WorldCharacter insert(WorldCharacter worldCharacter) {
        String sql =
                """
                INSERT INTO world_character
                (id, world_id, name, ai_settings, setting, intro, avatar, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                worldCharacter.getId(),
                worldCharacter.getWorldId(),
                worldCharacter.getName(),
                worldCharacter.getAiSettings() != null ? worldCharacter.getAiSettings() : "{}",
                worldCharacter.getSetting() != null ? worldCharacter.getSetting() : "",
                worldCharacter.getIntro() != null ? worldCharacter.getIntro() : "",
                worldCharacter.getAvatar() != null ? worldCharacter.getAvatar() : "",
                worldCharacter.getCreateTime().format(formatter),
                worldCharacter.getUpdateTime().format(formatter)
        );

        return selectById(worldCharacter.getId());
    }

    @Override
    public WorldCharacter update(WorldCharacter worldCharacter) {
        String sql =
                """
                UPDATE world_character
                SET name = ?, ai_settings = ?, setting = ?, intro = ?, avatar = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                worldCharacter.getName(),
                worldCharacter.getAiSettings() != null ? worldCharacter.getAiSettings() : "{}",
                worldCharacter.getSetting() != null ? worldCharacter.getSetting() : "",
                worldCharacter.getIntro() != null ? worldCharacter.getIntro() : "",
                worldCharacter.getAvatar() != null ? worldCharacter.getAvatar() : "",
                worldCharacter.getUpdateTime().format(formatter),
                worldCharacter.getId()
        );

        return selectById(worldCharacter.getId());
    }

    @Override
    public WorldCharacter deleteById(String id) {
        WorldCharacter worldCharacter = selectById(id);
        if (worldCharacter == null) {
            throw new RuntimeException("角色不存在");
        }
        String sql = "DELETE FROM world_character WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return worldCharacter;
    }

    @Override
    public void deleteByWorldId(String worldId) {
        String sql = "DELETE FROM world_character WHERE world_id = ?";
        jdbcTemplate.update(sql, worldId);
    }

    @Override
    public List<WorldCharacter> selectByWorldId(String worldId) {
        String sql = "SELECT * FROM world_character WHERE world_id = ? ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper, worldId);
    }

    @Override
    public WorldCharacter selectById(String id) {
        String sql = "SELECT * FROM world_character WHERE id = ?";
        List<WorldCharacter> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public WorldCharacter selectByWorldAndName(String worldId, String name) {
        String sql = "SELECT * FROM world_character WHERE world_id = ? AND name = ?";
        List<WorldCharacter> list = jdbcTemplate.query(sql, rowMapper, worldId, name);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }

    @Override
    public List<WorldCharacter> selectAll() {
        String sql = "SELECT * FROM world_character ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
