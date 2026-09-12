package com.fishsunny.assistant.plug.character.tool.dice;

/*
 * @Usage 骰子检定工具包 —— 为角色扮演场景提供 D20 / NDM 检定。
 *        默认开启（可用 plug.character.tool.dice.enable 关闭）。
 *        工具只面向角色对话：声明 excludeFromMainAgent() 让普通对话默认看不到；
 *        角色对话由 CharacterChatSocketHandler 的 toolProvider 按角色允许表重建（include 语义，
 *        不受 kit 可见性影响）。用户在设置页显式开启后主对话也能直接调用。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/14
 */

import com.fishsunny.assistant.engine.protocol.project.entity.ChatSession;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.plug.character.service.CharacterSessionBindings;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "plug.character.tool.dice.enable", havingValue = "true", matchIfMissing = true)
public class DiceToolKit extends ToolKit {

    public DiceToolKit(List<ToolHandler> tools, @Value("${plug.character.tool.dice.enable:true}") boolean enable) {
        super(tools, enable);
    }

    @Override
    public String displayName() {
        return "骰子检定";
    }

    @Override
    public String description() {
        return "D20 与 NDM 掷骰检定";
    }

    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }

    /**
     * 从上下文中解析角色 ID。如果当前会话未绑定角色则抛出异常。
     */
    public static String resolveCharacterId(Map<String, Object> context) throws ToolExecutor.ToolExecuteException {
        ChatSession chatSession = (ChatSession) context.get("chatSession");
        if (chatSession == null) {
            throw new ToolExecutor.ToolExecuteException("无法获取当前会话信息");
        }
        String characterId = CharacterSessionBindings.resolveCharacterId(chatSession);
        if (characterId == null) {
            throw new ToolExecutor.ToolExecuteException("当前会话未绑定角色");
        }
        return characterId;
    }
}
