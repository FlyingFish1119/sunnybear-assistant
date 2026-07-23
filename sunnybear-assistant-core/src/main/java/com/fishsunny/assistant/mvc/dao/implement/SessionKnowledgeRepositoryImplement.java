package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage session-知识库映射数据访问实现
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import com.fishsunny.assistant.engine.protocol.project.entity.SessionKnowledgeRecord;
import com.fishsunny.assistant.mvc.dao.SessionKnowledgeRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

@Repository
public class SessionKnowledgeRepositoryImplement implements SessionKnowledgeRepository {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    public SessionKnowledgeRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<SessionKnowledgeRecord> rowMapper = new RowMapper<>() {
        @Override
        public SessionKnowledgeRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
            SessionKnowledgeRecord record = new SessionKnowledgeRecord();
            record.setId(rs.getString("id"));
            record.setSessionId(rs.getString("session_id"));
            record.setKnowledgeIds(rs.getString("knowledge_ids"));
            record.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
            record.setUpdateTime(LocalDateTime.parse(rs.getString("update_time"), FORMATTER));
            return record;
        }
    };

    @Override
    public SessionKnowledgeRecord upsertBySessionId(SessionKnowledgeRecord record) {
        String now = LocalDateTime.now().format(FORMATTER);

        SessionKnowledgeRecord existing = selectBySessionId(record.getSessionId());
        if (existing != null) {
            // 更新已有记录
            String sql = """
                    UPDATE session_knowledge
                    SET knowledge_ids = ?,
                        update_time = ?
                    WHERE session_id = ?
                    """;
            jdbcTemplate.update(sql, record.getKnowledgeIds(), now, record.getSessionId());
        } else {
            // 插入新记录
            String sql = """
                    INSERT INTO session_knowledge (id, session_id, knowledge_ids, create_time, update_time)
                    VALUES (?, ?, ?, ?, ?)
                    """;
            String id = record.getId() != null ? record.getId() : UUID.randomUUID().toString();
            jdbcTemplate.update(sql, id, record.getSessionId(), record.getKnowledgeIds(), now, now);
        }

        return selectBySessionId(record.getSessionId());
    }

    @Override
    public SessionKnowledgeRecord selectBySessionId(String sessionId) {
        String sql = "SELECT * FROM session_knowledge WHERE session_id = ?";
        List<SessionKnowledgeRecord> results = jdbcTemplate.query(sql, rowMapper, sessionId);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void deleteBySessionId(String sessionId) {
        String sql = "DELETE FROM session_knowledge WHERE session_id = ?";
        jdbcTemplate.update(sql, sessionId);
    }
}
