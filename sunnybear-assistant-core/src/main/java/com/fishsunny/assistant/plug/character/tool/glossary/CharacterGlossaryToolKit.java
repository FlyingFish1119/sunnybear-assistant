package com.fishsunny.assistant.plug.character.tool.glossary;

/*
 * @Usage 角色词条工具包 —— 默认开启（可用 plug.character.tool.glossary.enable 关闭）。
 *        工具只面向角色对话：声明 excludeFromMainAgent() 让普通对话默认看不到；
 *        角色对话由 CharacterChatSocketHandler 的 toolProvider 按角色允许表重建（include 语义，
 *        不受 kit 可见性影响）。用户在设置页显式开启后主对话也能直接调用。
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/13
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.character.tool.glossary.enable", havingValue = "true", matchIfMissing = true)
public class CharacterGlossaryToolKit extends ToolKit {

    public CharacterGlossaryToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "角色词条";
    }

    @Override
    public String description() {
        return "角色词条的查询、生成、更新与删除";
    }

    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }
}
