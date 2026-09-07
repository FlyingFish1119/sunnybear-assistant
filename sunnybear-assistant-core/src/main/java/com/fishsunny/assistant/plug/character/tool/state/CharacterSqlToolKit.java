package com.fishsunny.assistant.plug.character.tool.state;

/*
 * @Usage 角色 SQL 工具包 —— 为角色提供私有 SQLite 沙箱数据库操作。
 *        默认开启（可用 plug.character.tool.sql.enable 关闭）。
 *        工具只面向角色对话：构造时把自己组的工具加进 ChatProcessor.EXCLUDE_TOOLS，
 *        使普通对话看不到；角色对话由 CharacterChatSocketHandler 的 toolProvider 按角色允许表重建。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.character.tool.sql.enable", havingValue = "true", matchIfMissing = true)
public class CharacterSqlToolKit extends ToolKit {

    public CharacterSqlToolKit(List<ToolHandler> tools, @Value("${plug.character.tool.sql.enable:true}") boolean enable) {
        super(tools, enable);
        if (enable) {
            ChatProcessor.getEXCLUDE_TOOLS().addAll(List.of(
                    SqlQueryTool.NAME,
                    SqlExecuteTool.NAME));
        }
    }
}
