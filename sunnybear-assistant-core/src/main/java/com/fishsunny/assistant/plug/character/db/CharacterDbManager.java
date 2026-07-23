package com.fishsunny.assistant.plug.character.db;

/*
 * @Usage 角色独立数据库管理器 —— 每个角色拥有独立的 SQLite 数据库文件，AI 可以在沙箱中自由使用标准 SQL
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class CharacterDbManager {

    private static final Logger log = LoggerFactory.getLogger(CharacterDbManager.class);

    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();
    private final String basePath;

    public CharacterDbManager(@Value("${assistant.file.base-path:data/}") String basePath) {
        this.basePath = basePath + "characters/";
    }

    /**
     * 获取或创建指定角色的独立 DataSource。
     * 数据库文件位于 data/characters/{characterId}/db/database.db，
     * 首次访问时自动创建目录和文件。
     */
    public DataSource getOrCreate(String characterId) {
        return dataSources.computeIfAbsent(characterId, cid -> {
            try {
                Path dbDir = Path.of(basePath, cid, "db");
                Files.createDirectories(dbDir);

                String jdbcUrl = "jdbc:sqlite:" + dbDir.resolve("database.db").toAbsolutePath().toString().replace("\\", "/");
                log.info("为角色 [{}] 创建独立数据库: {}", cid, jdbcUrl);

                HikariDataSource ds = new HikariDataSource();
                ds.setJdbcUrl(jdbcUrl);
                ds.setMaximumPoolSize(1);
                ds.setConnectionTimeout(5000);
                ds.setIdleTimeout(300000);
                return ds;
            } catch (Exception e) {
                throw new RuntimeException("无法为角色 [" + characterId + "] 创建数据库: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 关闭指定角色的数据库连接
     */
    public void close(String characterId) {
        HikariDataSource ds = dataSources.remove(characterId);
        if (ds != null) {
            ds.close();
            log.info("角色 [{}] 数据库连接已关闭", characterId);
        }
    }

    /**
     * 摧毁指定角色的数据库 —— 关闭连接池并删除数据库文件，用于重新开始新故事。
     */
    public String destroy(String characterId) {
        close(characterId);
        try {
            Path dbFile = Path.of(basePath, characterId, "db", "database.db");
            if (Files.exists(dbFile)) {
                Files.delete(dbFile);
                log.info("角色 [{}] 数据库文件已删除: {}", characterId, dbFile);
                return "数据库已摧毁，可以重新开始新故事";
            } else {
                log.info("角色 [{}] 数据库文件不存在，无需删除", characterId);
                return "数据库不存在，无需摧毁";
            }
        } catch (Exception e) {
            log.error("删除角色 [{}] 数据库文件失败", characterId, e);
            throw new RuntimeException("摧毁数据库失败: " + e.getMessage(), e);
        }
    }

    @PreDestroy
    public void closeAll() {
        dataSources.forEach((cid, ds) -> {
            ds.close();
        });
        dataSources.clear();
    }
}
