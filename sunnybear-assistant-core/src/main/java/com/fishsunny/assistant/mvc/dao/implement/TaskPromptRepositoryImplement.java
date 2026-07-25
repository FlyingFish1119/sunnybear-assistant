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
import java.util.List;

@Repository
public class TaskPromptRepositoryImplement implements TaskPromptRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskPromptRepositoryImplement.class);

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TaskPromptRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<TaskPrompt> rowMapper = new RowMapper<>() {
        @Override
        public TaskPrompt mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new TaskPrompt()
                    .setType(rs.getString("type"))
                    .setPrompt(rs.getString("prompt"))
                    .setDescription(rs.getString("description"));
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
        String sql = "INSERT INTO task_prompt (type, prompt, description) VALUES (?, ?, ?)";
        jdbcTemplate.update(sql, prompt.getType(), prompt.getPrompt(), prompt.getDescription());
    }

    @Override
    public void update(TaskPrompt prompt) {
        String sql = "UPDATE task_prompt SET prompt = ?, description = ? WHERE type = ?";
        jdbcTemplate.update(sql, prompt.getPrompt(), prompt.getDescription(), prompt.getType());
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
