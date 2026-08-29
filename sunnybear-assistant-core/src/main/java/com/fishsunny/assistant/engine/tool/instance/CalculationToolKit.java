package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/2 02:37
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import com.fishsunny.assistant.engine.tool.framwork.ToolKitComponent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

@ToolKitComponent(ToolKit.class)
@ConditionalOnProperty(name = "engine.tool.calc.enable", havingValue = "true", matchIfMissing = true)
public class CalculationToolKit extends ToolKit {

    public CalculationToolKit(List<ToolHandler> tools, @Value("${engine.tool.calc.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
