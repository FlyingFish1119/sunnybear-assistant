package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 02:02
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
public class ChatSessionRepositoryImplement implements ChatSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionRepositoryImplement.class);

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ChatSessionRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        // 自动迁移：为旧数据库添加 enable_pro 列
        try {
            jdbcTemplate.execute("ALTER TABLE chat_session ADD COLUMN enable_pro INTEGER NOT NULL DEFAULT 0");
            log.info("Migration: added enable_pro column to chat_session");
        } catch (Exception e) {
            log.debug("Migration: enable_pro column may already exist, skipping. {}", e.getMessage());
        }
    }

    private final RowMapper<ChatSession> rowMapper = (resultSet, i) -> {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(resultSet.getString("id"));
        chatSession.setName(resultSet.getString("name"));
        chatSession.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        chatSession.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        chatSession.setEnablePro(resultSet.getInt("enable_pro") == 1);
        return chatSession;
    };

    @Override
    public ChatSession insert(ChatSession chatSession) {
        String sql =
                """
                INSERT INTO chat_session
                (id, name, create_time, update_time, enable_pro)
                VALUES
                (?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                chatSession.getId(),
                chatSession.getName(),
                chatSession.getCreateTime().format(formatter),
                chatSession.getUpdateTime().format(formatter),
                chatSession.getEnablePro() != null && chatSession.getEnablePro() ? 1 : 0
        );

        ChatSession session = selectById(chatSession.getId());
        if (session == null) {
            throw new RuntimeException("Session not found");
        }
        return chatSession;
    }

    @Override
    public ChatSession update(ChatSession chatSession) {
        String sql =
                """
                UPDATE chat_session
                SET name = ?, update_time = ?, enable_pro = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                chatSession.getName(),
                chatSession.getUpdateTime().format(formatter),
                chatSession.getEnablePro() != null && chatSession.getEnablePro() ? 1 : 0,
                chatSession.getId()
        );

        ChatSession session = selectById(chatSession.getId());
        if (session == null) {
            throw new RuntimeException("Session not found");
        }

        return chatSession;
    }

    @Override
    @Transactional
    public ChatSession deleteById(String id) {
        ChatSession chatSession = selectById(id);
        if (chatSession == null) {
            throw new RuntimeException("Session not found");
        }
        String sql1 = "DELETE FROM chat_session WHERE id = ?";
        jdbcTemplate.update(sql1, id);

        String sql2 = "DELETE FROM chat_message WHERE session_id = ?";
        jdbcTemplate.update(sql2, id);

        return chatSession;
    }

    @Override
    public List<ChatSession> selectAll() {
        String sql = "SELECT * FROM chat_session ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public ChatSession selectById(String id) {
        String sql = "SELECT * FROM chat_session WHERE id = ?";
        ChatSession chatSession = jdbcTemplate.queryForObject(sql, rowMapper, id);
        if (chatSession == null) {
            throw new RuntimeException("Session not found");
        }
        return chatSession;
    }
}
