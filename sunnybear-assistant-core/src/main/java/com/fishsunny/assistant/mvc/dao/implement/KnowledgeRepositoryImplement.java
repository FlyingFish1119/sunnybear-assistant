package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 知识库数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fishsunny.assistant.engine.protocol.project.entity.KnowledgeRecord;
import com.fishsunny.assistant.mvc.dao.KnowledgeRepository;
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
public class KnowledgeRepositoryImplement implements KnowledgeRepository {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public KnowledgeRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 自动迁移：旧库删除 embedding 向量列（向量检索已下线）。
        // 新库由 schema.sql 直接按无 embedding 列建表（此处 DROP 因列不存在被吞，无需处理）。
        try {
            jdbcTemplate.execute("ALTER TABLE knowledge_entry DROP COLUMN embedding");
            log.info("Migration: dropped embedding column from knowledge_entry");
        } catch (Exception e) {
            log.debug("Migration: knowledge_entry embedding column may not exist, skipping. {}", e.getMessage());
        }
    }

    private final RowMapper<KnowledgeRecord> rowMapper = new RowMapper<>() {
        @Override
        public KnowledgeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeRecord record = new KnowledgeRecord();
            record.setId(rs.getInt("id"));
            record.setIntro(rs.getString("intro"));
            record.setContent(rs.getString("content"));
            record.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
            record.setUpdateTime(LocalDateTime.parse(rs.getString("update_time"), FORMATTER));
            return record;
        }
    };

    @Override
    public KnowledgeRecord insert(KnowledgeRecord record) {
        String sql = """
                INSERT INTO knowledge_entry (intro, content, create_time, update_time)
                VALUES (?, ?, ?, ?)
                """;

        String now = LocalDateTime.now().format(FORMATTER);

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.getIntro());
            ps.setString(2, record.getContent());
            ps.setString(3, now);
            ps.setString(4, now);
            return ps;
        }, keyHolder);

        int generatedId = Objects.requireNonNull(keyHolder.getKey()).intValue();
        return selectById(generatedId);
    }

    @Override
    public KnowledgeRecord update(KnowledgeRecord record) {
        String sql = """
                UPDATE knowledge_entry
                SET intro = ?,
                    content = ?,
                    update_time = ?
                WHERE id = ?
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        jdbcTemplate.update(sql, record.getIntro(), record.getContent(), now, record.getId());

        return selectById(record.getId());
    }

    @Override
    public KnowledgeRecord deleteById(Integer id) {
        KnowledgeRecord record = selectById(id);
        if (record == null) {
            return null;
        }
        String sql = "DELETE FROM knowledge_entry WHERE id = ?";
        jdbcTemplate.update(sql, id);
        return record;
    }

    @Override
    public KnowledgeRecord selectById(Integer id) {
        String sql = "SELECT id, intro, content, create_time, update_time FROM knowledge_entry WHERE id = ?";
        List<KnowledgeRecord> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<KnowledgeRecord> selectAll() {
        String sql = "SELECT id, intro, content, create_time, update_time FROM knowledge_entry ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
