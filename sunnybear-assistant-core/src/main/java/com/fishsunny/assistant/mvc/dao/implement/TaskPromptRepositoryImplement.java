package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 任务提示词数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/25
 */

import com.fishsunny.assistant.engine.protocol.project.entity.TaskPrompt;
import com.fishsunny.assistant.mvc.dao.TaskPromptRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class TaskPromptRepositoryImplement implements TaskPromptRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskPromptRepositoryImplement.class);

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TaskPromptRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 自动迁移：为旧数据库添加 create_time / update_time 列
        try {
            jdbcTemplate.execute("ALTER TABLE task_prompt ADD COLUMN create_time TEXT NOT NULL DEFAULT ''");
            log.info("Migration: added create_time column to task_prompt");
        } catch (Exception e) {
            log.debug("Migration: create_time column may already exist, skipping. {}", e.getMessage());
        }
        try {
            jdbcTemplate.execute("ALTER TABLE task_prompt ADD COLUMN update_time TEXT NOT NULL DEFAULT ''");
            log.info("Migration: added update_time column to task_prompt");
        } catch (Exception e) {
            log.debug("Migration: update_time column may already exist, skipping. {}", e.getMessage());
        }
    }

    private final RowMapper<TaskPrompt> rowMapper = new RowMapper<>() {
        @Override
        public TaskPrompt mapRow(ResultSet rs, int rowNum) throws SQLException {
            TaskPrompt tp = new TaskPrompt()
                    .setType(rs.getString("type"))
                    .setPrompt(rs.getString("prompt"))
                    .setDescription(rs.getString("description"));
            // create_time / update_time 可能为 null（兼容旧数据迁移的空字符串）
            String createTimeStr = rs.getString("create_time");
            if (createTimeStr != null && !createTimeStr.isEmpty()) {
                tp.setCreateTime(LocalDateTime.parse(createTimeStr, formatter));
            }
            String updateTimeStr = rs.getString("update_time");
            if (updateTimeStr != null && !updateTimeStr.isEmpty()) {
                tp.setUpdateTime(LocalDateTime.parse(updateTimeStr, formatter));
            }
            return tp;
        }
    };

    @Override
    public TaskPrompt selectByType(String type) {
        String sql = "SELECT * FROM task_prompt WHERE type = ?";
        List<TaskPrompt> results = jdbcTemplate.query(sql, rowMapper, type);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<TaskPrompt> selectAll() {
        String sql = "SELECT * FROM task_prompt ORDER BY type ASC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public void insert(TaskPrompt prompt) {
        String sql = "INSERT INTO task_prompt (type, prompt, description, create_time, update_time) VALUES (?, ?, ?, ?, ?)";
        String now = LocalDateTime.now().format(formatter);
        jdbcTemplate.update(sql, prompt.getType(), prompt.getPrompt(), prompt.getDescription(), now, now);
    }

    @Override
    public void update(TaskPrompt prompt) {
        String sql = "UPDATE task_prompt SET prompt = ?, description = ?, update_time = ? WHERE type = ?";
        String now = LocalDateTime.now().format(formatter);
        jdbcTemplate.update(sql, prompt.getPrompt(), prompt.getDescription(), now, prompt.getType());
    }

    @Override
    public TaskPrompt deleteByType(String type) {
        TaskPrompt existing = selectByType(type);
        if (existing == null) {
            return null;
        }
        String sql = "DELETE FROM task_prompt WHERE type = ?";
        jdbcTemplate.update(sql, type);
        return existing;
    }
}
