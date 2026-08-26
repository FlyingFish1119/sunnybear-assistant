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
import java.util.List;

@Repository
public class ChatMessageRepositoryImplement implements ChatMessageRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatMessageRepositoryImplement.class);

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;


    public ChatMessageRepositoryImplement(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
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
        return results.isEmpty() ? null : results.get(0);
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
        String sql = "DELETE FROM chat_message WHERE id IN (" + placeholders + ")";
        Object[] params = ids.toArray();
        jdbcTemplate.update(sql, params);
    }
}
