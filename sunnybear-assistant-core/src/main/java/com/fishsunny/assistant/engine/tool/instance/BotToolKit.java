package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/5 08:56
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.bot.enable", havingValue = "true", matchIfMissing = true)
public class BotToolKit extends ToolKit {

    public BotToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "桌面操控";
    }

    @Override
    public String description() {
        return "以操作链方式编排鼠标与键盘原语，一次性执行本机桌面操作";
    }

    /**
     * 桌面操控默认不对主对话开放：接管鼠标键盘有副作用，统一经 computer_use_tool 子 Agent
     * 在「先截屏观察、逐步操作、逐步确认」的流程里使用。用户在设置页可显式覆盖此默认值。
     */
    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }
}
