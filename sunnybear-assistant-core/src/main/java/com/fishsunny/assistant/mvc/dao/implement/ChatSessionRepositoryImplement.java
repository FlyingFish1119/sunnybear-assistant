package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/28 02:02
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.mvc.dao.ChatSessionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "local", matchIfMissing = true)
public class ChatSessionRepositoryImplement implements ChatSessionRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatSessionRepositoryImplement.class);

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, Object> extensionLocks = new ConcurrentHashMap<>();

    @Autowired
    public ChatSessionRepositoryImplement(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
        chatSession.setExtension(parseExtension(resultSet.getString("extension")));
        return chatSession;
    };

    /** DB TEXT 列（JSON 字符串）→ Map；空值/解析失败返回 null（视为未设置） */
    private Map<String, Object> parseExtension(String json) {
        if (!StringUtils.hasText(json)) {
            return null;
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("解析 chat_session.extension 失败: {}", e.getMessage());
            return null;
        }
    }

    /** Map → DB TEXT 列（JSON 字符串）；null 原样存 null */
    private String serializeExtension(Map<String, Object> extension) {
        if (extension == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(extension);
        } catch (Exception e) {
            log.warn("序列化 chat_session.extension 失败: {}", e.getMessage());
            return null;
        }
    }

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
                serializeExtension(chatSession.getExtension())
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
        // extension 不在此处更新：唯一入口是 mergeExtension（append-only 合并，避免抹掉其它消费方的 key）

        ChatSession session = selectById(chatSession.getId());
        if (session == null) {
            throw new RuntimeException("Session not found");
        }

        return chatSession;
    }

    @Override
    public Map<String, Object> mergeExtension(String id, Map<String, Object> fields) {
        if (!StringUtils.hasText(id)) {
            throw new IllegalArgumentException("会话 id 不能为空");
        }
        if (fields == null || fields.isEmpty()) {
            ChatSession current = selectById(id);
            return current == null ? new LinkedHashMap<>() : current.ensureExtension();
        }
        synchronized (extensionLocks.computeIfAbsent(id, k -> new Object())) {
            ChatSession current = selectById(id);
            if (current == null) {
                throw new RuntimeException("Session not found: " + id);
            }
            Map<String, Object> extension = current.ensureExtension();
            extension.putAll(fields);
            String merged;
            try {
                merged = objectMapper.writeValueAsString(extension);
            } catch (Exception e) {
                throw new RuntimeException("序列化会话 extension 失败: " + e.getMessage(), e);
            }
            jdbcTemplate.update("UPDATE chat_session SET extension = ? WHERE id = ?", merged, id);
            return extension;
        }
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
    public List<ChatSession> selectByTypePage(String type, int limit, String beforeTime, String beforeId) {
        // update_time 以 "yyyy-MM-dd HH:mm:ss" 文本存储，字典序即时间序，可直接字符串比较。
        // 游标 = (上一页最旧 update_time, 其 id)，id 仅作同秒内的稳定平局裁决，保证翻页不重不漏。
        String sql;
        Object[] args;
        if (beforeTime != null && beforeId != null) {
            sql = "SELECT * FROM chat_session WHERE type = ?"
                    + " AND (update_time < ? OR (update_time = ? AND id < ?))"
                    + " ORDER BY update_time DESC, id DESC LIMIT ?";
            args = new Object[]{type, beforeTime, beforeTime, beforeId, limit};
        } else {
            sql = "SELECT * FROM chat_session WHERE type = ? ORDER BY update_time DESC, id DESC LIMIT ?";
            args = new Object[]{type, limit};
        }
        return jdbcTemplate.query(sql, rowMapper, args);
    }

    @Override
    public ChatSession selectById(String id) {
        String sql = "SELECT * FROM chat_session WHERE id = ?";
        List<ChatSession> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public List<ChatSession> selectByTypeAndExtensionValue(String type, String jsonKey, String value) {
        String sql = "SELECT * FROM chat_session WHERE type = ? AND json_extract(extension, ?) = ? ORDER BY update_time DESC";
        return jdbcTemplate.query(sql, rowMapper, type, "$." + jsonKey, value);
    }
}
