package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage 流程工具集 - 用于流程控制（测试、等待等）
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/30
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.flow.enable", havingValue = "true", matchIfMissing = true)
public class FlowToolKit extends ToolKit {

    public FlowToolKit(List<ToolHandler> tools, @Value("${engine.tool.flow.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
