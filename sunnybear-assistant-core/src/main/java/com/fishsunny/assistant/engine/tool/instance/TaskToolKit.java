package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 06:56
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.task.enable", havingValue = "true", matchIfMissing = true)
public class TaskToolKit extends ToolKit {

    public TaskToolKit(List<ToolHandler> tools, @Value("${engine.tool.task.enable:true}") boolean enable) {
        super(tools, enable);
    }

    @Override
    public String displayName() {
        return "任务";
    }

    @Override
    public String description() {
        return "拆解并执行多步骤任务，维护步骤状态";
    }
}
