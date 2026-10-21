package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 项目仓储本地实现（SQLite）。写法与 ChatSessionRepositoryImplement 保持一致：
 *        时间列存 "yyyy-MM-dd HH:mm:ss" 文本，字段少所以 update 走整体覆盖。
 *        自动追加清单在表里是 JSON 数组文本，与 List<String> 的互转由本类负责。
 *        老库没有这一列：启动时查一次 PRAGMA table_info，缺了就补（见 ensureColumns）。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.mvc.dao.ChatProjectRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "assistant.remote-storage.role", havingValue = "local", matchIfMissing = true)
public class ChatProjectRepositoryImplement implements ChatProjectRepository {

    private static final Logger log = LoggerFactory.getLogger(ChatProjectRepositoryImplement.class);

    /** 自动追加清单的列名（老库缺的就是它） */
    private static final String COLUMN_AUTO_APPEND_FILES = "auto_append_files";

    /** SQLite 没有 ADD COLUMN IF NOT EXISTS，重复执行会报错，所以补列前必须先查列在不在 */
    private static final String DDL_ADD_AUTO_APPEND_FILES =
            "ALTER TABLE chat_project ADD COLUMN auto_append_files TEXT NOT NULL DEFAULT '[]'";

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper;

    @Autowired
    public ChatProjectRepositoryImplement(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 老库补列：建表用的是 CREATE TABLE IF NOT EXISTS，早就存在的库不会跟着 schema.sql 长出新列。
     * <p>先查列清单再决定补不补 —— 表已经建出来但缺列时才 ALTER，列在、表还没建出来都直接跳过。
     */
    @PostConstruct
    public void ensureColumns() {
        try {
            List<String> columns = jdbcTemplate.query("PRAGMA table_info(chat_project)",
                    (resultSet, i) -> resultSet.getString("name"));
            if (columns.isEmpty() || columns.contains(COLUMN_AUTO_APPEND_FILES)) {
                return;
            }
            jdbcTemplate.execute(DDL_ADD_AUTO_APPEND_FILES);
            log.info("chat_project 补列完成: {}", COLUMN_AUTO_APPEND_FILES);
        } catch (Exception e) {
            log.warn("chat_project 补列检查失败（老库可手工执行一次 {}）: {}", DDL_ADD_AUTO_APPEND_FILES, e.getMessage());
        }
    }

    private final RowMapper<ChatProject> rowMapper = (resultSet, i) -> {
        ChatProject project = new ChatProject();
        project.setId(resultSet.getString("id"));
        project.setName(resultSet.getString("name"));
        project.setDescription(resultSet.getString("description"));
        project.setColor(resultSet.getString("color"));
        project.setAutoAppendFiles(parseAutoAppendFiles(resultSet.getString(COLUMN_AUTO_APPEND_FILES)));
        project.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        project.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return project;
    };

    @Override
    public ChatProject insert(ChatProject project) {
        String sql =
                """
                INSERT INTO chat_project
                (id, name, description, color, auto_append_files, create_time, update_time)
                VALUES
                (?, ?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                project.getId(),
                project.getName(),
                project.getDescription() == null ? "" : project.getDescription(),
                project.getColor() == null ? "" : project.getColor(),
                formatAutoAppendFiles(project.getAutoAppendFiles()),
                project.getCreateTime().format(formatter),
                project.getUpdateTime().format(formatter));
        return selectById(project.getId());
    }

    @Override
    public ChatProject update(ChatProject project) {
        String sql =
                """
                UPDATE chat_project
                SET name = ?, description = ?, color = ?, auto_append_files = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                project.getName(),
                project.getDescription() == null ? "" : project.getDescription(),
                project.getColor() == null ? "" : project.getColor(),
                formatAutoAppendFiles(project.getAutoAppendFiles()),
                project.getUpdateTime().format(formatter),
                project.getId());
        return selectById(project.getId());
    }

    @Override
    public ChatProject deleteById(String id) {
        ChatProject project = selectById(id);
        jdbcTemplate.update("DELETE FROM chat_project WHERE id = ?", id);
        return project;
    }

    @Override
    public List<ChatProject> selectAll() {
        return jdbcTemplate.query("SELECT * FROM chat_project ORDER BY update_time DESC", rowMapper);
    }

    @Override
    public ChatProject selectById(String id) {
        List<ChatProject> list = jdbcTemplate.query("SELECT * FROM chat_project WHERE id = ?", rowMapper, id);
        return list.isEmpty() ? null : list.getFirst();
    }

    @Override
    public ChatProject selectByName(String name) {
        List<ChatProject> list = jdbcTemplate.query(
                "SELECT * FROM chat_project WHERE name = ? LIMIT 1", rowMapper, name);
        return list.isEmpty() ? null : list.getFirst();
    }

    /* ======================== 自动追加清单的 JSON 互转 ======================== */

    /** 清单 → JSON 数组文本；空清单写 {@code []}，转不动就退化成空清单（不让它拦了写库） */
    private String formatAutoAppendFiles(List<String> files) {
        if (files == null || files.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(files);
        } catch (Exception e) {
            log.warn("项目自动追加清单序列化失败: {}", e.getMessage());
            return "[]";
        }
    }

    /** JSON 数组文本 → 清单；空值、坏数据都退回空清单 */
    private List<String> parseAutoAppendFiles(String json) {
        if (!StringUtils.hasText(json)) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("项目自动追加清单解析失败: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
