package com.fishsunny.assistant.utils;

/*
 * @Usage chat_memory 表幂等迁移 —— 补 group_name 分组列，存量记忆自动回填「未分类」
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/23
 */

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 存量库迁移器：schema.sql 的 CREATE TABLE IF NOT EXISTS 只管新库建表，
 * 老库补列由这里在启动时完成 —— PRAGMA 查到没有 group_name 才 ALTER，
 * 有则跳过，幂等可反复执行。ALTER 带 NOT NULL DEFAULT 时 SQLite 会自动把
 * 存量行回填成默认值「未分类」，一条老记忆都不会丢。
 *
 * 执行时机：SQL init（schema.sql）在 DataSource 初始化阶段先跑，
 * 本类 @PostConstruct 晚于它，故表一定已存在。
 */
@Component
public class MemorySchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(MemorySchemaMigration.class);

    private final JdbcTemplate jdbcTemplate;

    public MemorySchemaMigration(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    public void migrate() {
        try {
            List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(chat_memory)");
            boolean hasGroup = columns.stream()
                    .anyMatch(col -> "group_name".equalsIgnoreCase(String.valueOf(col.get("name"))));
            if (hasGroup) {
                return;
            }
            jdbcTemplate.execute("ALTER TABLE chat_memory ADD COLUMN group_name TEXT NOT NULL DEFAULT '未分类'");
            log.info("chat_memory 分组列迁移完成：存量记忆已归入「未分类」");
        } catch (Exception e) {
            // 迁移失败不阻断启动；若确实缺列，后续首条 SQL 会把问题暴露在日志里
            log.warn("chat_memory 分组列迁移失败: {}", e.getMessage());
        }
    }
}
