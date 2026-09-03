package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage AI 安全审查相关的辅助工具集。当前装载解码工具 decode_tool，
 *        供安全审查子 Agent（也允许主 Agent）把被编码/混淆的内容还原后进行危险性判断。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/3
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.instance.security.DecodeTool;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "engine.tool.security.enable", havingValue = "true", matchIfMissing = true)
public class SecurityToolKit extends ToolKit {

    public SecurityToolKit(List<ToolHandler> tools, @Value("${engine.tool.security.enable:true}") boolean enable) {
        super(tools, enable);
        ChatProcessor.getEXCLUDE_TOOLS().add(DecodeTool.NAME);
    }
}
