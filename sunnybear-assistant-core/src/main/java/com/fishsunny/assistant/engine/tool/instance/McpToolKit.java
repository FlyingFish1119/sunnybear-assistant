package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage MCP 工具集 —— 将 MCP Server 的工具清单查询与远程工具调用暴露为 AI 可用工具
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/25 16:30
 */

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.List;

//TODO 这部分还需要好好打磨，暂时先这样用着:P
@ToolKitComponent(ToolKit.class)
@ConditionalOnProperty(name = "engine.tool.mcp.enable", havingValue = "true", matchIfMissing = true)
public class McpToolKit extends ToolKit {

    public McpToolKit(List<ToolHandler> tools, @Value("${engine.tool.mcp.enable:true}") boolean enable) {
        super(tools, enable);
    }
}
