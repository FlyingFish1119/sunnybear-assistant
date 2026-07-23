package com.fishsunny.assistant.plug.character.tool.dice;

/*
 * @Usage 状态机工具包 —— 为角色扮演场景提供数据库、计算和 D20 检定工具。
 *        需在配置中显式启用（engine.tool.state-machine.enable）。
 *        包含：角色私有 SQLite 沙箱数据库（查询/执行）、数学计算、D20 检定。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.plug.character.entity.CharacterSessionMapping;
import com.fishsunny.assistant.plug.character.service.CharacterSessionMappingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "plug.character.tool.dice.enable", havingValue = "true", matchIfMissing = false)
public class DiceToolKit extends ToolKit {

    public DiceToolKit(List<ToolHandler> tools, @Value("${plug.character.tool.dice.enable:false}") boolean enable) {
        super(tools, enable);
    }

    /**
     * 从上下文中解析角色 ID。如果当前会话未绑定角色则抛出异常。
     */
    public static String resolveCharacterId(Map<String, Object> context, CharacterSessionMappingService mappingService) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        if (chatSession == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话信息");
        }
        CharacterSessionMapping mapping = mappingService.findBySessionId(chatSession.getId());
        if (mapping == null) {
            throw new ToolExecutor.ToolExecuteException("当前会话未绑定角色");
        }
        return mapping.getCharacterId();
    }
}
