package com.fishsunny.assistant.mvc.dao.implement;

/*
 * @Usage 自动记忆整理待办的数据访问实现（本地 SQLite）
 *
 *        只在 role=local 时装配；remote 模式下整块功能静默不启用，避免多实例重复整理同一段对话。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 * @Date 2026/11/6
 */

import com.fishsunny.assistant.engine.protocol.project.entity.SessionMemoryJob;
import com.fishsunny.assistant.mvc.dao.SessionMemoryJobRepository;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Slf4j
@Repository
@ConditionalOnProperty(name = "assistant.remote-storage.role", havingValue = "local", matchIfMissing = true)
public class SessionMemoryJobRepositoryImplement implements SessionMemoryJobRepository {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final String CREATE_SQL = """
            CREATE TABLE IF NOT EXISTS session_memory_job (
                session_id        TEXT PRIMARY KEY,
                due_time          TEXT NOT NULL,
                last_message_time TEXT NOT NULL
            )
            """;

    private final JdbcTemplate jdbcTemplate;

    public SessionMemoryJobRepositoryImplement(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 兼容早期版本留下的表结构：那一版多了 status / attempts / result / create_time / update_time，
     * 其中 create_time、update_time 是 NOT NULL 且无默认值，新代码只插三列会直接插入失败。
     * <p>这张表只存调度元数据、没有任何业务数据，所以发现旧结构就直接重建，零损失。
     */
    @PostConstruct
    public void ensureStructure() {
        try {
            List<String> columns = jdbcTemplate.query("PRAGMA table_info(session_memory_job)",
                    (rs, rowNum) -> rs.getString("name"));
            if (columns.isEmpty() || !columns.contains("status")) {
                return;
            }
            jdbcTemplate.execute("DROP TABLE session_memory_job");
            jdbcTemplate.execute(CREATE_SQL);
            jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_session_memory_job_due ON session_memory_job(due_time)");
            log.info("自动记忆待办表已按新结构重建（旧版仅存调度元数据，无数据损失）");
        } catch (Exception e) {
            log.warn("自动记忆待办表结构检查失败: {}", e.getMessage());
        }
    }

    private final RowMapper<SessionMemoryJob> rowMapper = new RowMapper<>() {
        @Override
        public SessionMemoryJob mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new SessionMemoryJob()
                    .setSessionId(rs.getString("session_id"))
                    .setDueTime(parse(rs.getString("due_time")))
                    .setLastMessageTime(parse(rs.getString("last_message_time")));
        }
    };

    private LocalDateTime parse(String text) {
        return text == null ? null : LocalDateTime.parse(text, FORMATTER);
    }

    private String format(LocalDateTime time) {
        return time == null ? null : time.format(FORMATTER);
    }

    @Override
    public SessionMemoryJob selectBySessionId(String sessionId) {
        List<SessionMemoryJob> results = jdbcTemplate.query(
                "SELECT * FROM session_memory_job WHERE session_id = ?", rowMapper, sessionId);
        return results.isEmpty() ? null : results.getFirst();
    }

    @Override
    public List<SessionMemoryJob> selectAll() {
        return jdbcTemplate.query("SELECT * FROM session_memory_job", rowMapper);
    }

    @Override
    public void upsert(SessionMemoryJob job) {
        // 续期就是"这段对话重新开始计时"：一条语句覆盖到期时间与计时依据
        String sql = """
                INSERT INTO session_memory_job (session_id, due_time, last_message_time)
                VALUES (?, ?, ?)
                ON CONFLICT(session_id) DO UPDATE SET
                    due_time          = excluded.due_time,
                    last_message_time = excluded.last_message_time
                """;
        jdbcTemplate.update(sql, job.getSessionId(), format(job.getDueTime()), format(job.getLastMessageTime()));
    }

    @Override
    public boolean take(String sessionId, LocalDateTime lastMessageTime) {
        return jdbcTemplate.update(
                "DELETE FROM session_memory_job WHERE session_id = ? AND last_message_time = ?",
                sessionId, format(lastMessageTime)) > 0;
    }

    @Override
    public int deleteBySessionId(String sessionId) {
        return jdbcTemplate.update("DELETE FROM session_memory_job WHERE session_id = ?", sessionId);
    }
}
