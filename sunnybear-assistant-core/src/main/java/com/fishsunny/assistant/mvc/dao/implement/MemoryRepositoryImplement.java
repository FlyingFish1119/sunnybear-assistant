package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 核心记忆数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.mvc.dao.MemoryRepository;
import com.fishsunny.assistant.engine.protocol.project.entity.MemoryRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;

@Repository
public class MemoryRepositoryImplement implements MemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(MemoryRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public MemoryRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<MemoryRecord> rowMapper = new RowMapper<>() {
        @Override
        public MemoryRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            MemoryRecord record = new MemoryRecord();
            record.setId(rs.getInt("id"));
            record.setContent(rs.getString("content"));
            try {
                record.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
                record.setUpdateTime(LocalDateTime.parse(rs.getString("update_time"), FORMATTER));
            } catch (Exception e) {
                log.error("解析记忆时间失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析记忆时间失败: " + e.getMessage());
            }
            return record;
        }
    };

    @Override
    public MemoryRecord insert(MemoryRecord record) {
        String sql = """
                INSERT INTO chat_memory (content, create_time, update_time)
                VALUES (?, ?, ?)
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        KeyHolder keyHolder = new GeneratedKeyHolder();

        jdbcTemplate.update((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.getContent());
            ps.setString(2, now);
            ps.setString(3, now);
            return ps;
        }, keyHolder);

        int generatedId = Objects.requireNonNull(keyHolder.getKey()).intValue();
        return selectById(generatedId);
    }

    @Override
    public MemoryRecord update(MemoryRecord record) {
        String sql = """
                UPDATE chat_memory
                SET content = ?,
                    update_time = ?
                WHERE id = ?
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        jdbcTemplate.update(sql, record.getContent(), now, record.getId());

        return selectById(record.getId());
    }

    @Override
    public MemoryRecord deleteById(Integer id) {
        MemoryRecord record = selectById(id);
        if (record == null) {
            return null;
        }
        String sql = "DELETE FROM chat_memory WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return record;
    }

    @Override
    public MemoryRecord selectById(Integer id) {
        String sql = "SELECT * FROM chat_memory WHERE id = ?";
        List<MemoryRecord> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<MemoryRecord> selectAll() {
        String sql = "SELECT * FROM chat_memory ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
