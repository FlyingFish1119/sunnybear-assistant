package com.fishsunny.assistant.plug.character.tool.battle;

/*
 * @Usage
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/15 12:03
 */


import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.character.tool.battle.enable", havingValue = "true", matchIfMissing = false)
public class BattleToolKit extends ToolKit {

    public BattleToolKit(List<ToolHandler> tools, @Value("${plug.character.tool.battle.enable:false}") boolean enable) {
        super(tools, enable);
    }
}
