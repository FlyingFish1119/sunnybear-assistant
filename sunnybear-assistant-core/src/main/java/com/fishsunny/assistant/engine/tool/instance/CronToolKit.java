package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage 定时任务工具包
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/29
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.cron.enable", havingValue = "true", matchIfMissing = true)
public class CronToolKit extends ToolKit {

    public CronToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "定时任务";
    }

    @Override
    public String description() {
        return "按 cron 表达式创建与管理定时任务";
    }
}
