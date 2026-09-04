package com.fishsunny.assistant.plug.character.tool.battle;

/*
 * @Usage 战斗数据库查询工具 —— 对当前 session 的战斗临时数据库执行 SELECT 查询。
 *        DataSource 由 CharacterChatSocketHandler 的 contextProvider 自动注入。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolIncludeContext;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import lombok.Data;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.util.List;
import java.util.Map;

@ToolKitComponent(BattleToolKit.class)
@ConditionalOnExpression("${plug.character.tool.battle.enable:false} && ${plug.character.tool.battle.battle-query.enable:true}")
public class BattleSqlQueryTool implements ToolHandler {

    public static final String NAME = "battle_db_query";

    private static final Logger log = LoggerFactory.getLogger(BattleSqlQueryTool.class);
    private static final int MAX_ROWS = 100;

    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public BattleSqlQueryTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;

        register = new ToolRegister()
                .setName(NAME)
                .setDescription("""
                        查询当前战斗的临时数据库（仅限 SELECT 等只读语句），返回 Markdown 表格。""")
                .setRequired(List.of("sql"))
                .setParameters(List.of(
                        new ToolRegister.Parameters("sql", "string", "要执行的 SQL 查询语句，仅限只读操作（SELECT / PRAGMA / EXPLAIN）")
                ));
    }

    @Override
    @ToolIncludeContext(key = "battleDataSource", type = DataSource.class)
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

        // 本工具仅在战斗内置子 Agent 中执行，battleDataSource 必已注入（@ToolIncludeContext），直接取用
        DataSource ds = (DataSource) context.get("battleDataSource");

        try (Connection conn = ds.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            ResultSetMetaData meta = rs.getMetaData();
            int colCount = meta.getColumnCount();

            // 表头
            StringBuilder sb = new StringBuilder("|");
            for (int i = 1; i <= colCount; i++) {
                sb.append(" ").append(meta.getColumnName(i)).append(" |");
            }
            sb.append("\n|");
            sb.repeat(" --- |", Math.max(0, colCount));
            sb.append("\n");

            // 数据行
            int rowCount = 0;
            while (rs.next() && rowCount < MAX_ROWS) {
                sb.append("|");
                for (int i = 1; i <= colCount; i++) {
                    String val = rs.getString(i);
                    sb.append(" ").append(val != null ? val.replace("\n", "\\n").replace("\r", "") : "NULL").append(" |");
                }
                sb.append("\n");
                rowCount++;
            }

            if (rowCount == 0) {
                sb.append("*（查询结果为空）*\n");
            } else {
                sb.append("\n*共 ").append(rowCount).append(" 行");
                if (rowCount >= MAX_ROWS) {
                    sb.append("（已截断，最多返回 ").append(MAX_ROWS).append(" 行，请加 WHERE 条件缩小范围）");
                }
                sb.append("*\n");
            }

            log.debug("战斗数据库查询成功，返回 {} 行", rowCount);
            return new ToolExecutor.ToolExecuteResponse(NAME, sb.toString());
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.warn("战斗数据库查询失败: {}", msg);
            throw new ToolExecutor.ToolExecuteException("查询执行失败: " + msg + "\nSQL: " + sql);
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
