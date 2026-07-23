package com.fishsunny.assistant.plug.character.db;

/*
 * @Usage 战斗临时数据库管理器 —— 以 session 为粒度创建独立 SQLite 内存数据库。
 *        战斗开始时 CREATE 6 张表并 INSERT 初始数据，战斗期间直接 UPDATE/SELECT，
 *        战斗结束后关闭连接池即释放，无文件残留。战斗是"当下的"。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BattleDbManager {

    private static final Logger log = LoggerFactory.getLogger(BattleDbManager.class);

    public static final String PLAYER_STATE = "player_state";
    public static final String PLAYER_SKILLS = "player_skills";
    public static final String PLAYER_BUFFS = "player_buffs";
    public static final String ENEMY_STATE = "enemy_state";
    public static final String ENEMY_SKILLS = "enemy_skills";
    public static final String ENEMY_BUFFS = "enemy_buffs";

    /** 每场战斗 6 张表的建表语句 */
    static final String[] DDL = {
            "CREATE TABLE player_state (name TEXT, description TEXT, hp INTEGER, mp INTEGER, max_hp INTEGER, max_mp INTEGER)",
            "CREATE TABLE player_skills (id INTEGER PRIMARY KEY, name TEXT, description TEXT, cost INTEGER, damage_dice TEXT, effect TEXT, difficulty INTEGER)",
            "CREATE TABLE player_buffs (id INTEGER PRIMARY KEY, name TEXT, description TEXT, remaining_turns INTEGER DEFAULT -1)",
            "CREATE TABLE enemy_state (name TEXT, description TEXT, hp INTEGER, mp INTEGER, max_hp INTEGER, max_mp INTEGER)",
            "CREATE TABLE enemy_skills (id INTEGER PRIMARY KEY, name TEXT, description TEXT, cost INTEGER, damage_dice TEXT, effect TEXT, difficulty INTEGER)",
            "CREATE TABLE enemy_buffs (id INTEGER PRIMARY KEY, name TEXT, description TEXT, remaining_turns INTEGER DEFAULT -1)"
    };

    private final Map<String, HikariDataSource> dataSources = new ConcurrentHashMap<>();

    /**
     * 为指定 session 创建临时战斗数据库（纯内存，不落盘）。
     * 使用命名内存库模式（file:{name}?mode=memory&cache=shared），
     * 确保同一 DataSource 的所有连接共享同一个内存数据库。
     */
    public DataSource create(String characterId, String sessionId) {
        return dataSources.computeIfAbsent(sessionId, sid -> {
            try {
                String safeId = sid.replace("-", "");
                // 命名内存数据库：相同 URI 的连接共享同一份数据，池关闭即销毁
                String jdbcUrl = "jdbc:sqlite:file:" + safeId + "?mode=memory&cache=shared";
                log.info("创建战斗内存数据库: sessionId={}", sid);

                HikariDataSource ds = new HikariDataSource();
                ds.setJdbcUrl(jdbcUrl);
                ds.setMaximumPoolSize(1);
                ds.setConnectionTimeout(5000);
                return ds;
            } catch (Exception e) {
                throw new RuntimeException("无法为 session [" + sid + "] 创建战斗数据库: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 初始化 6 张表。
     */
    public void initTables(DataSource ds) throws Exception {
        try (var conn = ds.getConnection();
             var stmt = conn.createStatement()) {
            for (String ddl : DDL) {
                stmt.executeUpdate(ddl);
            }
        }
    }

    /**
     * 获取指定 session 的战斗 DataSource，不存在则返回 null。
     */
    public DataSource getDataSource(String sessionId) {
        return dataSources.get(sessionId);
    }

    /**
     * 摧毁指定 session 的战斗数据库 —— 关闭连接池，内存数据随即释放。
     */
    public void destroy(String characterId, String sessionId) {
        HikariDataSource ds = dataSources.remove(sessionId);
        if (ds != null) {
            ds.close();
            log.info("战斗内存数据库已释放: sessionId={}", sessionId);
        }
    }

    @PreDestroy
    public void closeAll() {
        dataSources.forEach((sid, ds) -> {
            ds.close();
            log.info("战斗内存数据库已释放（PreDestroy）: sessionId={}", sid);
        });
        dataSources.clear();
    }
}
