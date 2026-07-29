package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage 定时任务工具包
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fishsunny.assistant.engine.tool.framwork.ToolHandler;
import com.fishsunny.assistant.engine.tool.framwork.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.cron.enable", havingValue = "true", matchIfMissing = true)
public class CronToolKit extends ToolKit {

    public CronToolKit(List<ToolHandler> tools, @Value("${engine.tool.cron.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
