package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 任务数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5
 */

import com.fishsunny.assistant.mvc.dao.TaskRepository;
import com.fishsunny.assistant.engine.protocol.project.entity.Task;
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
public class TaskRepositoryImplement implements TaskRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TaskRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<Task> rowMapper = new RowMapper<>() {
        @Override
        public Task mapRow(ResultSet rs, int rowNum) throws SQLException {
            Task task = new Task();
            task.setId(rs.getString("id"));
            task.setTaskName(rs.getString("task_name"));
            task.setTaskDesc(rs.getString("task_desc"));
            task.setStatus(rs.getString("status"));
            try {
                task.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
                String finishTime = rs.getString("finish_time");
                if (finishTime != null) {
                    task.setFinishTime(LocalDateTime.parse(finishTime, FORMATTER));
                }
            } catch (Exception e) {
                log.error("解析任务时间失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析任务时间失败: " + e.getMessage());
            }
            return task;
        }
    };

    @Override
    public Task insert(Task task) {
        String sql = """
                INSERT INTO task (id, task_name, task_desc, status, create_time, finish_time)
                VALUES (?, ?, ?, ?, ?, ?)
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        String finishTime = task.getFinishTime() != null
                ? task.getFinishTime().format(FORMATTER)
                : null;

        jdbcTemplate.update(sql,
                task.getId(),
                task.getTaskName(),
                task.getTaskDesc(),
                task.getStatus(),
                now,
                finishTime
        );

        return selectById(task.getId());
    }

    @Override
    public Task updateStatus(String id, String status, LocalDateTime finishTime) {
        String sql = """
                UPDATE task
                SET status = ?,
                    finish_time = ?
                WHERE id = ?
                """;

        String finishTimeStr = finishTime != null
                ? finishTime.format(FORMATTER)
                : null;

        jdbcTemplate.update(sql, status, finishTimeStr, id);

        return selectById(id);
    }

    @Override
    public Task finishTask(String id, LocalDateTime finishTime) {
        String sql = """
                UPDATE task
                SET status = ?,
                    finish_time = ?
                WHERE id = ?
                """;

        String finishTimeStr = finishTime != null
                ? finishTime.format(FORMATTER)
                : null;

        jdbcTemplate.update(sql, Task.STATUS_FINISHED, finishTimeStr, id);

        return selectById(id);
    }

    @Override
    public Task deleteById(String id) {
        Task task = selectById(id);
        if (task == null) {
            return null;
        }
        String deleteTask = "DELETE FROM task WHERE id = ?";
        jdbcTemplate.update(deleteTask, id);

        return task;
    }

    @Override
    public Task selectById(String id) {
        String sql = "SELECT * FROM task WHERE id = ?";
        List<Task> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<Task> selectAll() {
        String sql = "SELECT * FROM task ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public List<Task> selectAll(int limit, int offset) {
        String sql = "SELECT * FROM task ORDER BY create_time DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, rowMapper, limit, offset);
    }

    @Override
    public int count() {
        String sql = "SELECT COUNT(*) FROM task";
        Integer count = jdbcTemplate.queryForObject(sql, Integer.class);
        return count != null ? count : 0;
    }
}
