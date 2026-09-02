package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage 浏览器工具包 - 提供无头浏览器自动化交互能力
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/22 10:00
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.browser.enable", havingValue = "true", matchIfMissing = true)
public class BrowserToolKit extends ToolKit {

    public BrowserToolKit(List<ToolHandler> tools, @Value("${engine.tool.browser.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
