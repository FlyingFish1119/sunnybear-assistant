package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/27 02:33
 */


import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.message.ChatMessage;
import com.fishsunny.assistant.mvc.dao.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ChatMessageRepositoryImplement implements ChatMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;


    public ChatMessageRepositoryImplement(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        // 自动迁移：为旧数据库添加 kid_index 列（分支记忆箭头），添加成功后按现有 active 状态回填。
        // 新库由 schema.sql 直接建列（此处 ADD 因重复列被吞，无需回填）。
        try {
            jdbcTemplate.execute("ALTER TABLE chat_message ADD COLUMN kid_index TEXT NULL");
            log.info("Migration: added kid_index column to chat_message");
            try {
                backfillKidIndex();
            } catch (Exception ex) {
                log.error("Migration: backfill kid_index failed", ex);
            }
        } catch (Exception e) {
            log.debug("Migration: kid_index column may already exist, skipping. {}", e.getMessage());
        }
    }

    /**
     * RowMapper：将数据库结果集映射为 ChatMessage 对象
     */
    private final RowMapper<ChatMessage> rowMapper = new RowMapper<>() {
        @Override
        public ChatMessage mapRow(ResultSet set, int rowNum) throws SQLException {
            ChatMessage msg = new ChatMessage();
            msg.setSessionId(set.getString("session_id"));
            msg.setId(set.getString("id"));
            msg.setParentId(set.getString("parent_id"));
            msg.setToolCallId(set.getString("tool_call_id"));
            msg.setName(set.getString("name"));
            msg.setRole(set.getString("role"));
            msg.setReasoningContent(set.getString("reasoning_content"));
            msg.setActive(set.getBoolean("active"));
            msg.setKidIndex(set.getString("kid_index"));

            try {
                String createTime = set.getString("create_time");
                msg.setCreateTime(LocalDateTime.parse(createTime, FORMATTER));

                msg.setContents(objectMapper.readValue(set.getString("contents"), new TypeReference<>() {
                }));

                String toolCalls = set.getString("tool_calls");
                if (StringUtils.hasText(toolCalls)) {
                    msg.setToolCalls(objectMapper.readValue(toolCalls, new TypeReference<>() {
                    }));
                }

                String extension = set.getString("extension");
                if (StringUtils.hasText(extension)) {
                    msg.setExtension(objectMapper.readValue(extension, new TypeReference<>() {
                    }));
                }
            } catch (Exception e) {
                log.error("解析数据库结果集失败: {}", e.getMessage(), e);
                throw new RuntimeException("解析数据库结果集失败: " + e.getMessage());
            }

            return msg;
        }
    };


    @Override
    public ChatMessage insert(ChatMessage chatMessage) {
        String sql =
                """
                INSERT INTO chat_message
                (session_id, id, parent_id, tool_call_id, name, role, reasoning_content, contents, tool_calls, extension, create_time)
                VALUES
                (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        String contentsJson;
        String toolCallsJson;
        String extensionJson;
        try {
            contentsJson = objectMapper.writeValueAsString(chatMessage.getContents());
            toolCallsJson = objectMapper.writeValueAsString(chatMessage.getToolCalls());
            extensionJson = objectMapper.writeValueAsString(chatMessage.getExtension());
        } catch (Exception e) {
            log.error("转换消息内容失败: {}", e.getMessage(), e);
            throw new RuntimeException("转换消息内容失败: " + e.getMessage());
        }

        jdbcTemplate.update(sql,
                chatMessage.getSessionId(),
                chatMessage.getId(),
                chatMessage.getParentId(),
                chatMessage.getToolCallId(),
                chatMessage.getName(),
                chatMessage.getRole(),
                chatMessage.getReasoningContent(),
                contentsJson,
                toolCallsJson,
                extensionJson,
                chatMessage.getCreateTime().format(FORMATTER)
        );

        return selectById(chatMessage.getId());
    }

    @Override
    public ChatMessage update(ChatMessage chatMessage) {
        String sql =
                """
                UPDATE chat_message
                SET parent_id = ?,
                    contents = ?,
                    active = ?
                WHERE id = ?
                """;

        String contentsJson;
        try {
            contentsJson = objectMapper.writeValueAsString(chatMessage.getContents());
        } catch (Exception e) {
            log.error("转换消息内容失败: {}", e.getMessage(), e);
            throw new RuntimeException("转换消息内容失败: " + e.getMessage());
        }

        jdbcTemplate.update(sql,
                chatMessage.getParentId(),
                contentsJson,
                chatMessage.getActive(),
                chatMessage.getId()
        );

        ChatMessage message = selectById(chatMessage.getId());
        if (message == null) {
            throw new RuntimeException("消息不存在");
        }
        return message;
    }

    @Override
    public ChatMessage replace(ChatMessage chatMessage) {
        String sql = """
                UPDATE chat_message
                SET parent_id = ?,
                    tool_call_id = ?,
                    name = ?,
                    role = ?,
                    reasoning_content = ?,
                    contents = ?,
                    active = ?,
                    tool_calls = ?,
                    extension = ?
                WHERE id = ?
                """;
        String contentsJson;
        try {
            contentsJson = objectMapper.writeValueAsString(chatMessage.getContents());
        } catch (Exception e) {
            log.error("转换消息内容失败: {}", e.getMessage(), e);
            throw new RuntimeException("转换消息内容失败: " + e.getMessage());
        }
        jdbcTemplate.update(sql,
                chatMessage.getParentId(),
                chatMessage.getToolCallId(),
                chatMessage.getName(),
                chatMessage.getRole(),
                chatMessage.getReasoningContent(),
                contentsJson,
                chatMessage.getActive(),
                chatMessage.getToolCalls(),
                chatMessage.getExtension(),
                chatMessage.getId()
        );
        return selectById(chatMessage.getId());
    }

    @Override
    public ChatMessage deleteById(String id) {
        ChatMessage chatMessage = selectById(id);
        if (chatMessage == null) {
            throw new RuntimeException("消息不存在");
        }

        String sql = "DELETE FROM chat_message WHERE id = ?";
        jdbcTemplate.update(sql, id);

        clearKidIndexPointers(List.of(chatMessage));
        return chatMessage;
    }

    @Override
    public int deleteBySessionId(String sessionId) {
        String sql = "DELETE FROM chat_message WHERE session_id = ?";
        return jdbcTemplate.update(sql, sessionId);
    }

    @Override
    public ChatMessage selectById(String id) {
        String sql = "SELECT * FROM chat_message WHERE id = ?";
        List<ChatMessage> results = jdbcTemplate.query(sql, rowMapper, id);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public List<ChatMessage> selectBySessionId(String sessionId) {
        String sql = "SELECT * FROM chat_message WHERE session_id = ? AND active = true ORDER BY create_time";
        return jdbcTemplate.query(sql, rowMapper, sessionId);
    }

    @Override
    public List<ChatMessage> selectSiblingsByParentId(String parentId, String sessionId) {
        if (parentId == null) {
            String sql = "SELECT * FROM chat_message WHERE parent_id IS NULL AND session_id = ? ORDER BY create_time";
            return jdbcTemplate.query(sql, rowMapper, sessionId);
        } else {
            String sql = "SELECT * FROM chat_message WHERE parent_id = ? ORDER BY create_time";
            return jdbcTemplate.query(sql, rowMapper, parentId);
        }
    }

    @Override
    public int updateActive(String id, boolean active) {
        String sql = "UPDATE chat_message SET active = ? WHERE id = ?";
        return jdbcTemplate.update(sql, active, id);
    }

    @Override
    public int batchUpdateActive(List<String> ids, boolean active) {
        if (ids == null || ids.isEmpty()) {
            return 0;
        }
        // 构建 IN 子句的占位符
        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        String sql = "UPDATE chat_message SET active = ? WHERE id IN (" + placeholders + ")";
        Object[] params = new Object[ids.size() + 1];
        params[0] = active;
        for (int i = 0; i < ids.size(); i++) {
            params[i + 1] = ids.get(i);
        }
        return jdbcTemplate.update(sql, params);
    }

    @Override
    public void deleteByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toArray(String[]::new));
        List<ChatMessage> toDelete = jdbcTemplate.query(
                "SELECT * FROM chat_message WHERE id IN (" + placeholders + ")",
                rowMapper, ids.toArray());
        String sql = "DELETE FROM chat_message WHERE id IN (" + placeholders + ")";
        Object[] params = ids.toArray();
        jdbcTemplate.update(sql, params);

        clearKidIndexPointers(toDelete);
    }

    @Override
    public void updateKidIndex(String id, String kidIndex) {
        String sql = "UPDATE chat_message SET kid_index = ? WHERE id = ?";
        jdbcTemplate.update(sql, kidIndex, id);
    }

    /**
     * 删除消息后，若其父的“分支记忆箭头”正指向被删消息，则清空（父重新成为有效尾部）。
     */
    private void clearKidIndexPointers(List<ChatMessage> deleted) {
        if (deleted == null || deleted.isEmpty()) {
            return;
        }
        for (ChatMessage m : deleted) {
            if (m.getParentId() != null) {
                jdbcTemplate.update(
                        "UPDATE chat_message SET kid_index = NULL WHERE id = ? AND kid_index = ?",
                        m.getParentId(), m.getId());
            }
        }
    }

    /**
     * 回填 kid_index：仅针对已存在数据的旧库执行（列刚被加上）。
     * <p>按现有 active 状态重建记忆箭头：
     * <ul>
     *   <li>父恰好 1 个活跃孩子 → 指向它（重建当前亮链）；</li>
     *   <li>父 ≥2 个活跃孩子（并行工具扇出）→ 置 NULL（回亮时整层全亮）；</li>
     *   <li>父 0 个活跃孩子（整支当前是暗的旧分支）→ 指向第一个孩子（退化今天的“首子”行为）。</li>
     * </ul>
     */
    private void backfillKidIndex() {
        List<ChatMessage> rows = jdbcTemplate.query(
                "SELECT id, parent_id, active, create_time FROM chat_message ORDER BY create_time, id",
                (rs, i) -> {
                    ChatMessage m = new ChatMessage();
                    m.setId(rs.getString("id"));
                    m.setParentId(rs.getString("parent_id"));
                    m.setActive(rs.getInt("active") == 1);
                    m.setCreateTime(LocalDateTime.parse(rs.getString("create_time"), FORMATTER));
                    return m;
                });

        Map<String, List<ChatMessage>> childrenByParent = new LinkedHashMap<>();
        for (ChatMessage row : rows) {
            if (row.getParentId() != null) {
                childrenByParent.computeIfAbsent(row.getParentId(), k -> new ArrayList<>()).add(row);
            }
        }

        int updated = 0;
        for (Map.Entry<String, List<ChatMessage>> entry : childrenByParent.entrySet()) {
            List<ChatMessage> children = entry.getValue();
            String kid = null;
            ChatMessage singleActive = null;
            int activeCount = 0;
            for (ChatMessage c : children) {
                if (Boolean.TRUE.equals(c.getActive())) {
                    activeCount++;
                    singleActive = c;
                }
            }
            if (activeCount == 1) {
                kid = singleActive.getId();
            } else if (activeCount == 0) {
                kid = children.get(0).getId();
            }
            jdbcTemplate.update("UPDATE chat_message SET kid_index = ? WHERE id = ?", kid, entry.getKey());
            updated++;
        }
        log.info("Migration: backfilled kid_index for {} parent nodes", updated);
    }
}
