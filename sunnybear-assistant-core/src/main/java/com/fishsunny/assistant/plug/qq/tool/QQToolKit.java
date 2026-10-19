package com.fishsunny.assistant.plug.qq.tool;

/*
 * @Usage QQ 通知工具集 —— 让 AI 具备主动发消息的能力
 *
 * @Project sunnybear-assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/10/19
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.qq.tool.enable", havingValue = "true", matchIfMissing = true)
public class QQToolKit extends ToolKit {

    public QQToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "QQ 通知";
    }

    @Override
    public String description() {
        return "主动通过 QQ 发送消息，用于提醒、通知与定时任务的结果上报";
    }

    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }
}
