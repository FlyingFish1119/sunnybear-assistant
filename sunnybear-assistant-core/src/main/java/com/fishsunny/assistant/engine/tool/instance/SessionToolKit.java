package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage Session 工具包 —— 管理会话文件相关的工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/7
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.session.enable", havingValue = "true", matchIfMissing = true)
public class SessionToolKit extends ToolKit {

    public SessionToolKit(List<ToolHandler> tools, @Value("${engine.tool.session.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
