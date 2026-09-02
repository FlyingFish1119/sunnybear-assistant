package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/1 22:36
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.image.enable", havingValue = "true", matchIfMissing = true)
public class ImageToolKit extends ToolKit {

    public ImageToolKit(List<ToolHandler> tools, @Value("${engine.tool.image.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
