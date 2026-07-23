package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage 核心记忆工具包
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/3
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.memory.enable", havingValue = "true", matchIfMissing = true)
public class MemoryToolKit extends ToolKit {

    public MemoryToolKit(List<ToolHandler> tools, @Value("${engine.tool.memory.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
