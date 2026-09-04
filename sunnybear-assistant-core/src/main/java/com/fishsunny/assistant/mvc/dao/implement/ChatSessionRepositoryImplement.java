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
        // 自动迁移：为旧数据库添加 type 列
        try {
            jdbcTemplate.execute("ALTER TABLE chat_session ADD COLUMN type TEXT NOT NULL DEFAULT 'chat'");
            log.info("Migration: added type column to chat_session");
        } catch (Exception e) {
            log.debug("Migration: type column may already exist, skipping. {}", e.getMessage());
        }
        // 自动迁移：为旧数据库添加 unreviewed 列（无审查模式）
        try {
            jdbcTemplate.execute("ALTER TABLE chat_session ADD COLUMN unreviewed INTEGER NOT NULL DEFAULT 0");
            log.info("Migration: added unreviewed column to chat_session");
        } catch (Exception e) {
            log.debug("Migration: unreviewed column may already exist, skipping. {}", e.getMessage());
        }
        // 自动迁移：为旧数据库添加 extension 列（插件扩展字段，语义由各插件自行约定）
        try {
            jdbcTemplate.execute("ALTER TABLE chat_session ADD COLUMN extension TEXT");
            log.info("Migration: added extension column to chat_session");
        } catch (Exception e) {
            log.debug("Migration: extension column may already exist, skipping. {}", e.getMessage());
        }
    }

    private final RowMapper<ChatSession> rowMapper = (resultSet, i) -> {
        ChatSession chatSession = new ChatSession();
        chatSession.setId(resultSet.getString("id"));
        chatSession.setName(resultSet.getString("name"));
        chatSession.setType(resultSet.getString("type"));
        chatSession.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        chatSession.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        chatSession.setEnablePro(resultSet.getInt("enable_pro") == 1);
        chatSession.setUnreviewed(resultSet.getInt("unreviewed") == 1);
        chatSession.setExtension(resultSet.getString("extension"));
        return chatSession;
    };

    @Override
    public ChatSession insert(ChatSession chatSession) {
        String sql =
                """
                INSERT INTO chat_session
                (id, name, type, create_time, update_time, enable_pro, unreviewed, extension)
                VALUES
                (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                chatSession.getId(),
                chatSession.getName(),
                chatSession.getType() != null ? chatSession.getType() : "chat",
                chatSession.getCreateTime().format(formatter),
                chatSession.getUpdateTime().format(formatter),
                chatSession.getEnablePro() != null && chatSession.getEnablePro() ? 1 : 0,
                chatSession.getUnreviewed() != null && chatSession.getUnreviewed() ? 1 : 0,
                chatSession.getExtension()
        );

        ChatSession session = selectById(chatSession.getId());
        if (session == null) {
            throw new RuntimeException("Session not found");
        }
        return chatSession;
    }

    @Override
    public ChatSession update(ChatSession chatSession) {
        // name/update_time 始终更新
        jdbcTemplate.update(
                "UPDATE chat_session SET name = ?, update_time = ? WHERE id = ?",
                chatSession.getName(),
                chatSession.getUpdateTime().format(formatter),
                chatSession.getId()
        );
        // enable_pro / unreviewed 仅在请求体显式携带时更新（避免前端改名等只带 name 的请求把开关重置为 0）
        if (chatSession.getEnablePro() != null) {
            jdbcTemplate.update("UPDATE chat_session SET enable_pro = ? WHERE id = ?",
                    chatSession.getEnablePro() ? 1 : 0, chatSession.getId());
        }
        if (chatSession.getUnreviewed() != null) {
            jdbcTemplate.update("UPDATE chat_session SET unreviewed = ? WHERE id = ?",
                    chatSession.getUnreviewed() ? 1 : 0, chatSession.getId());
        }

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
        String sql = "SELECT * FROM chat_session WHERE type = 'chat' ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper);
    }

    @Override
    public List<ChatSession> selectByType(String type) {
        String sql = "SELECT * FROM chat_session WHERE type = ? ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper, type);
    }

    @Override
    public ChatSession selectById(String id) {
        String sql = "SELECT * FROM chat_session WHERE id = ?";
        List<ChatSession> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public List<ChatSession> selectByTypeAndExtensionValue(String type, String jsonKey, String value) {
        String sql = "SELECT * FROM chat_session WHERE type = ? AND json_extract(extension, ?) = ? ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper, type, "$." + jsonKey, value);
    }
}
