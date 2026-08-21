package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 定时任务数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fishsunny.assistant.engine.protocol.project.entity.CronJob;
import com.fishsunny.assistant.mvc.dao.CronJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

@Repository
public class CronJobRepositoryImplement implements CronJobRepository {

    private static final Logger log = LoggerFactory.getLogger(CronJobRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public CronJobRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<CronJob> rowMapper = new RowMapper<>() {
        @Override
        public CronJob mapRow(ResultSet rs, int rowNum) throws SQLException {
            CronJob cronJob = new CronJob()
                    .setId(rs.getInt("id"))
                    .setTitle(rs.getString("title"))
                    .setDescription(rs.getString("description"))
                    .setCron(rs.getString("cron"))
                    .setMessage(rs.getString("message"))
                    .setEnablePro(rs.getInt("enable_pro") == 1);
            try {
                cronJob.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
                cronJob.setUpdateTime(LocalDateTime.parse(rs.getString("update_time"), FORMATTER));
            } catch (Exception e) {
                log.error("解析定时任务时间失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析定时任务时间失败: " + e.getMessage());
            }
            return cronJob;
        }
    };

    @Override
    public CronJob insert(CronJob cronJob) {
        String sql = """
                INSERT INTO cron_job (title, description, cron, message, enable_pro, create_time, update_time)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, cronJob.getTitle());
            ps.setString(2, cronJob.getDescription() != null ? cronJob.getDescription() : "");
            ps.setString(3, cronJob.getCron());
            ps.setString(4, cronJob.getMessage());
            ps.setInt(5, cronJob.getEnablePro() != null && cronJob.getEnablePro() ? 1 : 0);
            ps.setString(6, now);
            ps.setString(7, now);
            return ps;
        }, keyHolder);

        int generatedId = Objects.requireNonNull(keyHolder.getKey()).intValue();
        return selectById(generatedId);
    }

    @Override
    public CronJob update(CronJob cronJob) {
        String sql = """
                UPDATE cron_job
                SET title = ?,
                    description = ?,
                    cron = ?,
                    message = ?,
                    enable_pro = ?,
                    update_time = ?
                WHERE id = ?
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        jdbcTemplate.update(sql,
                cronJob.getTitle(),
                cronJob.getDescription() != null ? cronJob.getDescription() : "",
                cronJob.getCron(),
                cronJob.getMessage(),
                cronJob.getEnablePro() != null && cronJob.getEnablePro() ? 1 : 0,
                now,
                cronJob.getId());

        return selectById(cronJob.getId());
    }

    @Override
    public CronJob deleteById(Integer id) {
        CronJob cronJob = selectById(id);
        if (cronJob == null) {
            return null;
        }
        String sql = "DELETE FROM cron_job WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return cronJob;
    }

    @Override
    public CronJob selectById(Integer id) {
        String sql = "SELECT * FROM cron_job WHERE id = ?";
        List<CronJob> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<CronJob> selectAll() {
        String sql = "SELECT * FROM cron_job ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
