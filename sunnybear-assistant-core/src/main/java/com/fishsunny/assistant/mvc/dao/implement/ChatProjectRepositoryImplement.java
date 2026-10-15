package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 项目仓储本地实现（SQLite）。写法与 ChatSessionRepositoryImplement 保持一致：
 *        时间列存 "yyyy-MM-dd HH:mm:ss" 文本，字段少所以 update 走整体覆盖。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/15
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatProject;
import com.fishsunny.assistant.mvc.dao.ChatProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Repository
@ConditionalOnProperty(name = "assistant.remote-storage.mode", havingValue = "local", matchIfMissing = true)
public class ChatProjectRepositoryImplement implements ChatProjectRepository {

    private final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbcTemplate;

    @Autowired
    public ChatProjectRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private final RowMapper<ChatProject> rowMapper = (resultSet, i) -> {
        ChatProject project = new ChatProject();
        project.setId(resultSet.getString("id"));
        project.setName(resultSet.getString("name"));
        project.setDescription(resultSet.getString("description"));
        project.setColor(resultSet.getString("color"));
        project.setCreateTime(LocalDateTime.parse(resultSet.getString("create_time"), formatter));
        project.setUpdateTime(LocalDateTime.parse(resultSet.getString("update_time"), formatter));
        return project;
    };

    @Override
    public ChatProject insert(ChatProject project) {
        String sql =
                """
                INSERT INTO chat_project
                (id, name, description, color, create_time, update_time)
                VALUES
                (?, ?, ?, ?, ?, ?)
                """;
        jdbcTemplate.update(sql,
                project.getId(),
                project.getName(),
                project.getDescription() == null ? "" : project.getDescription(),
                project.getColor() == null ? "" : project.getColor(),
                project.getCreateTime().format(formatter),
                project.getUpdateTime().format(formatter));
        return selectById(project.getId());
    }

    @Override
    public ChatProject update(ChatProject project) {
        String sql =
                """
                UPDATE chat_project
                SET name = ?, description = ?, color = ?, update_time = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                project.getName(),
                project.getDescription() == null ? "" : project.getDescription(),
                project.getColor() == null ? "" : project.getColor(),
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
}
