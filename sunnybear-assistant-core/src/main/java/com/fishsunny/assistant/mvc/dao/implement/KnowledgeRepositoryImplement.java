package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 知识库数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final ObjectMapper objectMapper;

    public KnowledgeRepositoryImplement(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    private final RowMapper<KnowledgeRecord> rowMapper = new RowMapper<>() {
        @Override
        public KnowledgeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            KnowledgeRecord record = new KnowledgeRecord();
            record.setId(rs.getInt("id"));
            record.setIntro(rs.getString("intro"));
            record.setContent(rs.getString("content"));
            try {
                String embeddingJson = rs.getString("embedding");
                if (embeddingJson != null && !embeddingJson.isBlank()) {
                    record.setEmbedding(objectMapper.readValue(embeddingJson, new TypeReference<List<Float>>() {}));
                }
                record.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
                record.setUpdateTime(LocalDateTime.parse(rs.getString("update_time"), FORMATTER));
            } catch (Exception e) {
                log.error("解析知识条目数据失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析知识条目数据失败: " + e.getMessage());
            }
            return record;
        }
    };

    @Override
    public KnowledgeRecord insert(KnowledgeRecord record) {
        String sql = """
                INSERT INTO knowledge_entry (intro, content, embedding, create_time, update_time)
                VALUES (?, ?, ?, ?, ?)
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        String embeddingJson;
        try {
            embeddingJson = objectMapper.writeValueAsString(record.getEmbedding());
        } catch (Exception e) {
            throw new RuntimeException("序列化 embedding 失败: " + e.getMessage());
        }

        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update((Connection con) -> {
            PreparedStatement ps = con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, record.getIntro());
            ps.setString(2, record.getContent());
            ps.setString(3, embeddingJson);
            ps.setString(4, now);
            ps.setString(5, now);
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
                    embedding = ?,
                    update_time = ?
                WHERE id = ?
                """;

        String now = LocalDateTime.now().format(FORMATTER);
        String embeddingJson;
        try {
            embeddingJson = objectMapper.writeValueAsString(record.getEmbedding());
        } catch (Exception e) {
            throw new RuntimeException("序列化 embedding 失败: " + e.getMessage());
        }

        jdbcTemplate.update(sql, record.getIntro(), record.getContent(), embeddingJson, now, record.getId());

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
        String sql = "SELECT * FROM knowledge_entry WHERE id = ?";
        List<KnowledgeRecord> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<KnowledgeRecord> selectAll() {
        String sql = "SELECT * FROM knowledge_entry ORDER BY create_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }
}
