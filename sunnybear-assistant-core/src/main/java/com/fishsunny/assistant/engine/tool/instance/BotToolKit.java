package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 08:56
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.bot.enable", havingValue = "true", matchIfMissing = true)
public class BotToolKit extends ToolKit {

    public BotToolKit(List<ToolHandler> tools, @Value("${engine.tool.bot.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
