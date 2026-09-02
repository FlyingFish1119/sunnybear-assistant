package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 17:34
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.net.enable", havingValue = "true", matchIfMissing = true)
public class NetToolKit extends ToolKit {

    public NetToolKit(List<ToolHandler> tools, @Value("${engine.tool.net.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
