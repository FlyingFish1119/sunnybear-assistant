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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.view.enable", havingValue = "true", matchIfMissing = true)
public class ViewToolKit extends ToolKit {

    public ViewToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "视觉";
    }

    @Override
    public String description() {
        return "图片/视频内容识别与屏幕截图";
    }
}
