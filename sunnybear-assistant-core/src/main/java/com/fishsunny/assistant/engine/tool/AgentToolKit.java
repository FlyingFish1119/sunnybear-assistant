package com.fishsunny.assistant.engine.tool;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.agent.enable", havingValue = "true", matchIfMissing = true)
public class AgentToolKit extends ToolKit {

    public AgentToolKit(List<ToolHandler> tools, @Value("${engine.tool.agent.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
