package com.fishsunny.assistant.plug.character.repository;

/*
 * @Usage 角色信息数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class CharacterInfoRepositoryImplement implements CharacterInfoRepository, InitializingBean {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public CharacterInfoRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<CharacterInfo> rowMapper = (resultSet, i) -> {
        CharacterInfo characterInfo = new CharacterInfo();
        characterInfo.setId(resultSet.getString("id"));
        characterInfo.setName(resultSet.getString("name"));
        characterInfo.setAvatar(resultSet.getString("avatar"));
        characterInfo.setBackground(resultSet.getString("background"));
        characterInfo.setAiSettings(resultSet.getString("ai_settings"));
        characterInfo.setPreset(resultSet.getString("preset"));
        characterInfo.setMainColor(resultSet.getString("main_color"));
        characterInfo.setOpacity(resultSet.getDouble("opacity"));
        characterInfo.setTools(resultSet.getString("tools"));
        characterInfo.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        characterInfo.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return characterInfo;
    };

    @Override
    public CharacterInfo insert(CharacterInfo characterInfo) {
        String sql =
                """
                INSERT INTO character_info
                (id, name, avatar, background, ai_settings, preset, main_color, opacity, tools, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                characterInfo.getId(),
                characterInfo.getName(),
                characterInfo.getAvatar() != null ? characterInfo.getAvatar() : "",
                characterInfo.getBackground() != null ? characterInfo.getBackground() : "",
                characterInfo.getAiSettings() != null ? characterInfo.getAiSettings() : "{}",
                characterInfo.getPreset() != null ? characterInfo.getPreset() : "",
                characterInfo.getMainColor() != null ? characterInfo.getMainColor() : "",
                characterInfo.getOpacity() != null ? characterInfo.getOpacity() : 0.85,
                characterInfo.getTools() != null ? characterInfo.getTools() : "{}",
                characterInfo.getCreateTime().format(formatter),
                characterInfo.getUpdateTime().format(formatter)
        );

        return selectById(characterInfo.getId());
    }

    @Override
    public CharacterInfo update(CharacterInfo characterInfo) {
        String sql =
                """
                UPDATE character_info
                SET name = ?, avatar = ?, background = ?, ai_settings = ?, preset = ?, main_color = ?, opacity = ?, tools = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                characterInfo.getName(),
                characterInfo.getAvatar() != null ? characterInfo.getAvatar() : "",
                characterInfo.getBackground() != null ? characterInfo.getBackground() : "",
                characterInfo.getAiSettings() != null ? characterInfo.getAiSettings() : "{}",
                characterInfo.getPreset() != null ? characterInfo.getPreset() : "",
                characterInfo.getMainColor() != null ? characterInfo.getMainColor() : "",
                characterInfo.getOpacity() != null ? characterInfo.getOpacity() : 0.85,
                characterInfo.getTools() != null ? characterInfo.getTools() : "{}",
                characterInfo.getUpdateTime().format(formatter),
                characterInfo.getId()
        );

        return selectById(characterInfo.getId());
    }

    @Override
    public void afterPropertiesSet() {
        // 安全迁移：为存量数据库添加 tools 列（如果不存在）
        try {
            jdbcTemplate.execute("ALTER TABLE character_info ADD COLUMN tools TEXT NOT NULL DEFAULT '{}'");
        } catch (Exception ignored) {
            // 列已存在，后续启动时忽略错误
        }
    }

    @Override
    public CharacterInfo deleteById(String id) {
        CharacterInfo characterInfo = selectById(id);
        if (characterInfo == null) {
            throw new RuntimeException("角色不存在");
        }
        String sql = "DELETE FROM character_info WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return characterInfo;
    }

    @Override
    public void updateBackground(String id, String background) {
        String sql = "UPDATE character_info SET background = ?, update_time = ? WHERE id = ?";
        jdbcTemplate.update(sql,
                background != null ? background : "",
                LocalDateTime.now().format(formatter),
                id);
    }

    @Override
    public List<CharacterInfo> selectAll() {
        String sql = "SELECT * FROM character_info ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public CharacterInfo selectById(String id) {
        String sql = "SELECT * FROM character_info WHERE id = ?";
        List<CharacterInfo> list = jdbcTemplate.query(sql, rowMapper, id);
        if (list == null || list.isEmpty()) {
            return null;
        }
        return list.get(0);
    }
}
