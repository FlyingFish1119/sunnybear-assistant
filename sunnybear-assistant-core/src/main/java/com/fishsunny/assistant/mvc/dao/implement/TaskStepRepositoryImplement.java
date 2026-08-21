package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 任务步骤数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5
 */

import com.fishsunny.assistant.engine.protocol.project.entity.Task;
import com.fishsunny.assistant.engine.protocol.project.entity.TaskStep;
import com.fishsunny.assistant.mvc.dao.TaskStepRepository;
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
import java.util.ArrayList;
import java.util.List;

@Repository
public class TaskStepRepositoryImplement implements TaskStepRepository {

    private static final Logger log = LoggerFactory.getLogger(TaskStepRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public TaskStepRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<TaskStep> rowMapper = new RowMapper<>() {
        @Override
        public TaskStep mapRow(ResultSet rs, int rowNum) throws SQLException {
            TaskStep step = new TaskStep();
            step.setId(rs.getString("id"));
            step.setTaskId(rs.getString("task_id"));
            step.setStepName(rs.getString("step_name"));
            step.setStepDesc(rs.getString("step_desc"));
            step.setResult(rs.getString("result"));
            step.setStatus(rs.getString("status"));
            step.setSort(rs.getInt("sort"));
            try {
                String createTime = rs.getString("create_time");
                if (createTime != null) {
                    step.setCreateTime(LocalDateTime.parse(createTime, FORMATTER));
                }
            } catch (Exception e) {
                log.error("解析步骤创建时间失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析步骤创建时间失败: " + e.getMessage());
            }
            try {
                String finishTime = rs.getString("finish_time");
                if (finishTime != null) {
                    step.setFinishTime(LocalDateTime.parse(finishTime, FORMATTER));
                }
            } catch (Exception e) {
                log.error("解析步骤时间失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析步骤时间失败: " + e.getMessage());
            }
            return step;
        }
    };

    @Override
    public TaskStep insert(TaskStep step) {
        String sql = """
                INSERT INTO task_step (id, task_id, step_name, step_desc, result, status, sort, create_time, finish_time)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String createTime = step.getCreateTime() != null
                ? step.getCreateTime().format(FORMATTER)
                : null;
        String finishTime = step.getFinishTime() != null
                ? step.getFinishTime().format(FORMATTER)
                : null;

        jdbcTemplate.update(sql,
                step.getId(),
                step.getTaskId(),
                step.getStepName(),
                step.getStepDesc(),
                step.getResult(),
                step.getStatus(),
                step.getSort(),
                createTime,
                finishTime
        );

        return selectById(step.getId());
    }

    @Override
    public TaskStep updateStatus(String id, String status, LocalDateTime finishTime) {
        String sql = """
                UPDATE task_step
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
    public TaskStep finishStep(String id, String result, LocalDateTime finishTime) {
        String sql = """
                UPDATE task_step
                SET result = ?,
                    status = ?,
                    finish_time = ?
                WHERE id = ?
                """;

        String finishTimeStr = finishTime != null
                ? finishTime.format(FORMATTER)
                : null;

        jdbcTemplate.update(sql, result, Task.STATUS_FINISHED, finishTimeStr, id);

        return selectById(id);
    }

    @Override
    public TaskStep updateStep(String id, String stepName, String stepDesc, Integer sort) {
        StringBuilder sql = new StringBuilder("UPDATE task_step SET ");
        List<Object> params = new ArrayList<>();

        if (stepName != null) {
            sql.append("step_name = ?, ");
            params.add(stepName);
        }
        if (stepDesc != null) {
            sql.append("step_desc = ?, ");
            params.add(stepDesc);
        }
        if (sort != null) {
            sql.append("sort = ?, ");
            params.add(sort);
        }

        // 移除末尾逗号和空格
        if (params.isEmpty()) {
            return selectById(id);
        }
        sql.setLength(sql.length() - 2);

        sql.append(" WHERE id = ?");
        params.add(id);

        jdbcTemplate.update(sql.toString(), params.toArray());

        return selectById(id);
    }

    @Override
    public TaskStep updateSort(String id, int sort) {
        String sql = "UPDATE task_step SET sort = ? WHERE id = ?";
        jdbcTemplate.update(sql, sort, id);
        return selectById(id);
    }

    @Override
    public TaskStep deleteById(String id) {
        TaskStep step = selectById(id);
        if (step == null) {
            return null;
        }
        String sql = "DELETE FROM task_step WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return step;
    }

    @Override
    public List<TaskStep> deleteByTaskId(String taskId) {
        List<TaskStep> steps = selectByTaskId(taskId);
        if (steps.isEmpty()) {
            return new ArrayList<>();
        }
        String sql = "DELETE FROM task_step WHERE task_id = ?";
        jdbcTemplate.update(sql, taskId);
        return steps;
    }

    @Override
    public TaskStep selectById(String id) {
        String sql = "SELECT * FROM task_step WHERE id = ?";
        List<TaskStep> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<TaskStep> selectByTaskId(String taskId) {
        String sql = "SELECT * FROM task_step WHERE task_id = ? ORDER BY sort ASC";
        return jdbcTemplate.query(sql, rowMapper, taskId);
    }

    @Override
    public List<TaskStep> selectAll() {
        String sql = "SELECT * FROM task_step ORDER BY sort ASC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
