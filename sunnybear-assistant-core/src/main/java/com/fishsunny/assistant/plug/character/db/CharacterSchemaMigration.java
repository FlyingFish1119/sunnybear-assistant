package com.fishsunny.assistant.plug.character.db;

/*
 * @Usage character_info 表幂等迁移 —— 补 setting 列，并把存量 ai_settings.prompt 搬入该列、
 *        从 ai_settings 中摘除。一次启动搬完，之后 ai_settings 不再含设定文本。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/29
 */

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

/**
 * 存量库迁移器：
 * <ol>
 *     <li>schema.sql 的 CREATE TABLE IF NOT EXISTS 只管新库建表，老库补 setting 列由这里完成
 *         （PRAGMA 查列，缺才 ALTER），幂等可反复执行；</li>
 *     <li>把老数据里 ai_settings JSON 的 prompt 键搬到 setting 列，并从 ai_settings 中摘除 ——
 *         启动时一次搬完，之后该键不再存在。setting 已有值时以 setting 为准（不覆盖）。</li>
 * </ol>
 * 读写 JSON 一律走 JsonNode：AISettings.prompt 已删除，不能再用对象反序列化取该键。
 *
 * <p>执行时机：SQL init（schema.sql）在 DataSource 初始化阶段先跑，本类 @PostConstruct 晚于它，
 * 故表一定已存在。
 */
@Component
public class CharacterSchemaMigration {

    private static final Logger log = LoggerFactory.getLogger(CharacterSchemaMigration.class);

    private static final String TABLE = "character_info";
    private static final String COLUMN = "setting";
    private static final String PROMPT_KEY = "prompt";

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public CharacterSchemaMigration(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void migrate() {
        try {
            addSettingColumnIfAbsent();
            movePromptToSetting();
        } catch (Exception e) {
            // 迁移失败不阻断启动；若确实缺列，后续首条 SQL 会把问题暴露在日志里
            log.warn("character_info setting 列迁移失败: {}", e.getMessage());
        }
    }

    /** 老库补 setting 列；新库建表时已带上，直接跳过 */
    private void addSettingColumnIfAbsent() {
        List<Map<String, Object>> columns = jdbcTemplate.queryForList("PRAGMA table_info(" + TABLE + ")");
        boolean exists = columns.stream()
                .anyMatch(col -> COLUMN.equalsIgnoreCase(String.valueOf(col.get("name"))));
        if (exists) {
            return;
        }
        jdbcTemplate.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + COLUMN + " TEXT NOT NULL DEFAULT ''");
        log.info("character_info 补列完成: {}", COLUMN);
    }

    /** 存量数据搬迁：ai_settings.prompt → setting，并从 ai_settings 摘除该键 */
    private void movePromptToSetting() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, setting, ai_settings FROM " + TABLE + " WHERE ai_settings LIKE ?",
                "%\"" + PROMPT_KEY + "\"%");
        int moved = 0;
        for (Map<String, Object> row : rows) {
            String id = String.valueOf(row.get("id"));
            String aiSettingsJson = row.get("ai_settings") == null ? null : String.valueOf(row.get("ai_settings"));
            String setting = row.get("setting") == null ? "" : String.valueOf(row.get("setting"));
            if (!StringUtils.hasText(aiSettingsJson)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(aiSettingsJson);
                // LIKE 只是粗筛（prompt 可能出现在 customFields 等嵌套层），顶层没有该键就跳过
                if (!(root instanceof ObjectNode objectNode) || !objectNode.has(PROMPT_KEY)) {
                    continue;
                }
                String prompt = objectNode.path(PROMPT_KEY).asText("");
                objectNode.remove(PROMPT_KEY);
                String merged = StringUtils.hasText(setting) ? setting : prompt;
                jdbcTemplate.update("UPDATE " + TABLE + " SET setting = ?, ai_settings = ? WHERE id = ?",
                        merged, objectMapper.writeValueAsString(objectNode), id);
                moved++;
            } catch (Exception e) {
                log.warn("角色 [{}] 的 ai_settings.prompt 迁移失败: {}", id, e.getMessage());
            }
        }
        if (moved > 0) {
            log.info("character_info 设定迁移完成：{} 个角色的 ai_settings.prompt 已搬入 setting 列", moved);
        }
    }
}
