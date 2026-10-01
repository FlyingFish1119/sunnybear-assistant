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
import jakarta.annotation.PostConstruct;
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

    /**
     * 启动时自动迁移 chat_session 表结构（仅本地模式）：schema.sql 的 CREATE TABLE IF NOT EXISTS
     * 只管新库建表，老库补列在这里完成 —— PRAGMA 查到没有 cron_id 才 ALTER，有则跳过，幂等可反复执行。
     * <p>
     * 补列后做一次性回填：老库的 cron 会话创建时没记 cron_id，但名字固定是「任务标题_yyyyMMdd_HHmmss」，
     * 因此可按 cron_job.title 前缀匹配回填。新建的 cron 会话已在创建时直接写入 cron_id，不受影响。
     * <p>
     * 迁移失败不阻断启动：若确实缺列，后续首条 SQL 会把问题暴露在日志里。
     */
    @PostConstruct
    public void migrateSchema() {
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(chat_session)");
            boolean hasCronId = columns.stream()
                    .anyMatch(col -> "cron_id".equalsIgnoreCase(String.valueOf(col.get("name"))));
            if (!hasCronId) {
                jdbcTemplate.execute("ALTER TABLE chat_session ADD COLUMN cron_id INTEGER");
                log.info("chat_session.cron_id 列迁移完成");
            }
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_chat_session_cron_id ON chat_session(cron_id)");
            backfillCronId();
        } catch (Exception e) {
            log.warn("chat_session 自动迁移失败: {}", e.getMessage());
        }
    }

    /** 按任务标题前缀回填存量 cron 会话的 cron_id；仅处理 cron_id IS NULL 的行，重复执行安全 */
    private void backfillCronId() {
        List<Map<String, Object>> jobs = jdbcTemplate.queryForList("SELECT id, title FROM cron_job");
        int total = 0;
        for (Map<String, Object> job : jobs) {
            Object idObj = job.get("id");
            Object titleObj = job.get("title");
            if (idObj == null || titleObj == null) {
                continue;
            }
            // 会话名 = title + "_" + 时间戳，故匹配 title + "\_" + 任意后缀
            String prefix = escapeLike(String.valueOf(titleObj)) + "\\_%";
            int updated = jdbcTemplate.update(
                    "UPDATE chat_session SET cron_id = ?"
                            + " WHERE type = 'cron' AND cron_id IS NULL AND name LIKE ? ESCAPE '\\'",
                    ((Number) idObj).intValue(), prefix);
            total += updated;
        }
        if (total > 0) {
            log.info("chat_session.cron_id 回填完成：{} 条历史 cron 会话已归组", total);
        }
    }

    /** 转义 LIKE 模式中的通配符（配合 ESCAPE '\' 使用） */
    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
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
        int cronId = resultSet.getInt("cron_id");
        chatSession.setCronId(resultSet.wasNull() ? null : cronId);
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
                (id, name, type, create_time, update_time, enable_pro, unreviewed, cron_id, extension)
                VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                chatSession.getId(),
                chatSession.getName(),
                chatSession.getType() != null ? chatSession.getType() : "chat",
                chatSession.getCreateTime().format(formatter),
                chatSession.getUpdateTime().format(formatter),
                chatSession.getEnablePro() != null && chatSession.getEnablePro() ? 1 : 0,
                chatSession.getUnreviewed() != null && chatSession.getUnreviewed() ? 1 : 0,
                chatSession.getCronId(),
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
    public List<ChatSession> selectByCronIdPage(Integer cronId, int limit, String beforeTime, String beforeId) {
        // 与 selectByTypePage 同一套 keyset 游标语义，只是过滤条件换成 cron_id：
        // update_time 文本字典序即时间序，游标 = (上一页最旧 update_time, 其 id)，保证翻页不重不漏。
        String sql;
        Object[] args;
        if (beforeTime != null && beforeId != null) {
            sql = "SELECT * FROM chat_session WHERE type = 'cron' AND cron_id = ?"
                    + " AND (update_time < ? OR (update_time = ? AND id < ?))"
                    + " ORDER BY update_time DESC, id DESC LIMIT ?";
            args = new Object[]{cronId, beforeTime, beforeTime, beforeId, limit};
        } else {
            sql = "SELECT * FROM chat_session WHERE type = 'cron' AND cron_id = ?"
                    + " ORDER BY update_time DESC, id DESC LIMIT ?";
            args = new Object[]{cronId, limit};
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
