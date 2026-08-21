package com.fishsunny.assistant.plug.character.tool.state;

/*
 * @Usage 角色 SQL 工具包 —— 为角色提供私有 SQLite 沙箱数据库操作。
 *        需在配置中显式启用（plug.character.tool.sql.enable）。
 *        包含：角色数据库查询（character_sql_query）、角色数据库执行（character_sql_execute）。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.character.tool.sql.enable", havingValue = "true", matchIfMissing = false)
public class CharacterSqlToolKit extends ToolKit {

    public CharacterSqlToolKit(List<ToolHandler> tools, @Value("${plug.character.tool.sql.enable:false}") boolean enable) {
        super(tools, enable);
    }
}
