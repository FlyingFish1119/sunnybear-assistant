package com.fishsunny.assistant.plug.character.tool.state;

/*
 * @Usage 角色数据库执行工具 —— AI 对当前角色的私有 SQLite 数据库执行写入操作（DDL / DML）
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import com.fishsunny.assistant.plug.character.db.CharacterDbManager;
import com.fishsunny.assistant.plug.character.entity.CharacterInfo;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@ToolKitComponent(CharacterSqlToolKit.class)
@ConditionalOnExpression("${plug.character.tool.sql.enable:false} && ${plug.character.tool.sql.execute.enable:true}")
public class SqlExecuteTool implements ToolHandler {

    public static final String NAME = "character_db_execute";

    private static final Logger log = LoggerFactory.getLogger(SqlExecuteTool.class);

    private final ToolRegister register;
    private final ObjectMapper objectMapper;
    private final CharacterDbManager dbManager;

    public SqlExecuteTool(ObjectMapper objectMapper,
                          CharacterDbManager dbManager) {
        this.objectMapper = objectMapper;
        this.dbManager = dbManager;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        对当前角色的私有 SQLite 数据库执行写入操作。支持：\
                        CREATE TABLE / ALTER TABLE / DROP TABLE — 建表改表；\
                        INSERT / UPDATE / DELETE — 增删改数据；\
                        CREATE INDEX / DROP INDEX — 索引管理。\
                        建表时请使用标准 SQLite 语法，支持 INTEGER PRIMARY KEY AUTOINCREMENT、NOT NULL、UNIQUE、DEFAULT、CHECK 等约束。\
                        建议先用 character_db_query 执行 SELECT name FROM sqlite_master WHERE type='table' 了解现有表结构。""")
                .setRequired(List.of("sql"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("sql", "string", "要执行的 SQL 写入语句（CREATE / INSERT / UPDATE / DELETE / DROP / ALTER 等）")
                ));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        String sql = arguments.getSql();
        if (sql == null || sql.isBlank()) {
            throw new ToolExecutor.ToolExecuteException("参数 sql 不能为空");
        }
        sql = sql.trim();

        CharacterInfo characterInfo = (CharacterInfo) context.get("character");
        if (characterInfo == null) {
            throw new ToolExecutor.ToolExecuteException("角色信息未找到");
        }

        DataSource ds = dbManager.getOrCreate(characterInfo.getId());

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            int affected = stmt.executeUpdate(sql);
            StringBuilder result = new StringBuilder("执行成功。");
            if (affected >= 0) {
                result.append(" 影响了 ").append(affected).append(" 行。");
            }

            return new ToolExecutor.ToolExecuteResponse(NAME, result.toString());

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("角色 [{}] 写入执行失败: {}", characterInfo.getId(), msg);
            throw new ToolExecutor.ToolExecuteException("执行失败: " + msg + "\nSQL: " + sql);
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }


    @Data
    private static class Arguments {
        private String sql;
    }
}
