package com.fishsunny.assistant.plug.world.repository;

/*
 * @Usage 世界观数据访问实现
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/27
 */

import com.fishsunny.assistant.plug.world.entity.WorldInfo;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class WorldInfoRepositoryImplement implements WorldInfoRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public WorldInfoRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<WorldInfo> rowMapper = (resultSet, i) -> {
        WorldInfo worldInfo = new WorldInfo();
        worldInfo.setId(resultSet.getString("id"));
        worldInfo.setName(resultSet.getString("name"));
        worldInfo.setDescription(resultSet.getString("description"));
        worldInfo.setPreset(resultSet.getString("preset"));
        worldInfo.setBackground(resultSet.getString("background"));
        worldInfo.setMainColor(resultSet.getString("main_color"));
        worldInfo.setNarrationEnable(resultSet.getInt("narration_enable") != 0);
        worldInfo.setPossessName(resultSet.getString("possess_name"));
        worldInfo.setMaxRounds(resultSet.getInt("max_rounds"));
        worldInfo.setSchedulerAiSettings(resultSet.getString("scheduler_ai_settings"));
        worldInfo.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        worldInfo.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return worldInfo;
    };

    @Override
    public WorldInfo insert(WorldInfo worldInfo) {
        String sql =
                """
                INSERT INTO world_info
                (id, name, description, preset, background, main_color, narration_enable, possess_name, max_rounds, scheduler_ai_settings, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                worldInfo.getId(),
                worldInfo.getName(),
                worldInfo.getDescription() != null ? worldInfo.getDescription() : "",
                worldInfo.getPreset() != null ? worldInfo.getPreset() : "",
                worldInfo.getBackground() != null ? worldInfo.getBackground() : "",
                worldInfo.getMainColor() != null ? worldInfo.getMainColor() : "",
                Boolean.TRUE.equals(worldInfo.getNarrationEnable()) ? 1 : 0,
                worldInfo.getPossessName() != null ? worldInfo.getPossessName() : "",
                worldInfo.getMaxRounds() != null ? worldInfo.getMaxRounds() : 5,
                worldInfo.getSchedulerAiSettings() != null ? worldInfo.getSchedulerAiSettings() : "{}",
                worldInfo.getCreateTime().format(formatter),
                worldInfo.getUpdateTime().format(formatter)
        );

        return selectById(worldInfo.getId());
    }

    @Override
    public WorldInfo update(WorldInfo worldInfo) {
        String sql =
                """
                UPDATE world_info
                SET name = ?, description = ?, preset = ?, background = ?, main_color = ?, narration_enable = ?, possess_name = ?, max_rounds = ?, scheduler_ai_settings = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                worldInfo.getName(),
                worldInfo.getDescription() != null ? worldInfo.getDescription() : "",
                worldInfo.getPreset() != null ? worldInfo.getPreset() : "",
                worldInfo.getBackground() != null ? worldInfo.getBackground() : "",
                worldInfo.getMainColor() != null ? worldInfo.getMainColor() : "",
                Boolean.TRUE.equals(worldInfo.getNarrationEnable()) ? 1 : 0,
                worldInfo.getPossessName() != null ? worldInfo.getPossessName() : "",
                worldInfo.getMaxRounds() != null ? worldInfo.getMaxRounds() : 5,
                worldInfo.getSchedulerAiSettings() != null ? worldInfo.getSchedulerAiSettings() : "{}",
                worldInfo.getUpdateTime().format(formatter),
                worldInfo.getId()
        );

        return selectById(worldInfo.getId());
    }

    @Override
    public WorldInfo deleteById(String id) {
        WorldInfo worldInfo = selectById(id);
        if (worldInfo == null) {
            throw new RuntimeException("世界观不存在");
        }
        String sql = "DELETE FROM world_info WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return worldInfo;
    }


    @Override
    public void updateBackground(String id, String background) {
        String sql = "UPDATE world_info SET background = ?, update_time = ? WHERE id = ?";
        jdbcTemplate.update(sql,
                background != null ? background : "",
                LocalDateTime.now().format(formatter),
                id);
    }

    @Override
    public List<WorldInfo> selectAll() {
        String sql = "SELECT * FROM world_info ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public WorldInfo selectById(String id) {
        String sql = "SELECT * FROM world_info WHERE id = ?";
        List<WorldInfo> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list.isEmpty()) {
            return null;
        }
        return list.getFirst();
    }
}
