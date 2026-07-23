package com.fishsunny.assistant.plug.character.tool.battle;

/*
 * @Usage 战斗数据库写入工具 —— 对当前 session 的战斗临时数据库执行写入操作（DDL / DML）。
 *        DataSource 由 CharacterChatSocketHandler 的 contextProvider 自动注入。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framwork.ToolRegister;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@ToolKitComponent(BattleToolKit.class)
@ConditionalOnExpression("${plug.character.tool.battle.enable:false} && ${plug.character.tool.battle.battle-execute.enable:true}")
public class BattleSqlExecuteTool implements ToolHandler {

    public static final String NAME = "battle_db_execute";

    private static final Logger log = LoggerFactory.getLogger(BattleSqlExecuteTool.class);

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public BattleSqlExecuteTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        写入当前战斗的临时数据库（INSERT/UPDATE/DELETE/CREATE 等）。""")
                .setRequired(List.of("sql"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("sql", "string", "要执行的 SQL 写入语句（INSERT / UPDATE / DELETE / CREATE / ALTER / DROP 等）")
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

        DataSource ds = (DataSource) context.get("battleDataSource");
        if (ds == null) {
            throw new ToolExecutor.ToolExecuteException("当前没有活跃的战斗会话，无法写入战斗数据库");
        }

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement()) {

            int affected = stmt.executeUpdate(sql);
            StringBuilder result = new StringBuilder("执行成功。");
            if (affected >= 0) {
                result.append(" 影响了 ").append(affected).append(" 行。");
            }

            log.info("战斗数据库写入成功，影响 {} 行: {}", affected, sql.substring(0, Math.min(200, sql.length())));
            return new ToolExecutor.ToolExecuteResponse(NAME, result.toString());

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("战斗数据库写入失败: {}", msg);
            throw new ToolExecutor.ToolExecuteException("执行失败: " + msg + "\nSQL: " + sql);
        }
    }

    @Override
    public String name() { return NAME; }

    @Override
    public ToolRegister getRegister() { return register; }

    @Data
    private static class Arguments {
        private String sql;
    }
}
