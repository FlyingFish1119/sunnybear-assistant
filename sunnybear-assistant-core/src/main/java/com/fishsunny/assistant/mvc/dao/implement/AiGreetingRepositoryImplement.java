package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage AI 问候语数据访问层实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.protocol.project.entity.AiGreeting;
import com.fishsunny.assistant.mvc.dao.AiGreetingRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class AiGreetingRepositoryImplement implements AiGreetingRepository {

    private static final Logger log = LoggerFactory.getLogger(AiGreetingRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public AiGreetingRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<AiGreeting> rowMapper = new RowMapper<>() {
        @Override
        public AiGreeting mapRow(ResultSet set, int rowNum) throws SQLException {
            AiGreeting greeting = new AiGreeting();
            greeting.setId(set.getString("id"));
            greeting.setText(set.getString("text"));
            greeting.setGreetingTime(set.getString("greeting_time"));
            try {
                String createTime = set.getString("create_time");
                greeting.setCreateTime(LocalDateTime.parse(createTime, FORMATTER));
            } catch (Exception e) {
                log.error("解析问候语创建时间失败: {}", e.getMessage(), e);
            }
            return greeting;
        }
    };

    @Override
    public AiGreeting insert(AiGreeting greeting) {
        String sql = """
                INSERT INTO ai_greeting (id, text, greeting_time, create_time)
                VALUES (?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                greeting.getId(),
                greeting.getText(),
                greeting.getGreetingTime(),
                greeting.getCreateTime().format(FORMATTER)
        );
        return selectById(greeting.getId());
    }

    @Override
    public AiGreeting selectById(String id) {
        String sql = "SELECT * FROM ai_greeting WHERE id = ?";
        List<AiGreeting> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public AiGreeting selectRandom() {
        String sql = "SELECT * FROM ai_greeting ORDER BY RANDOM() LIMIT 1";
        List<AiGreeting> results = jdbcTemplate.query(sql, rowMapper);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public AiGreeting selectByGreetingTime(String greetingTime) {
        String sql = "SELECT * FROM ai_greeting WHERE greeting_time = ? ORDER BY RANDOM() LIMIT 1";
        List<AiGreeting> results = jdbcTemplate.query(sql, rowMapper, greetingTime);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<AiGreeting> selectAll() {
        String sql = "SELECT * FROM ai_greeting ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public int deleteById(String id) {
        String sql = "DELETE FROM ai_greeting WHERE id = ?";
        return jdbcTemplate.update(sql, id);
    }

    @Override
    public int deleteBefore(LocalDateTime cutoff) {
        String sql = "DELETE FROM ai_greeting WHERE create_time < ?";
        return jdbcTemplate.update(sql, cutoff.format(FORMATTER));
    }
}
