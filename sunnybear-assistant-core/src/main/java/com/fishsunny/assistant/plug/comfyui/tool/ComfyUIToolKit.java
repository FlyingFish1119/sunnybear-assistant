package com.fishsunny.assistant.plug.comfyui.tool;

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.comfyui.tool.enable", havingValue = "true", matchIfMissing = true)
public class ComfyUIToolKit extends ToolKit {

    public ComfyUIToolKit(List<ToolHandler> tools,
                          @Value("${plug.comfyui.tool.enable:true}") boolean enable) {
        super(tools, enable);
        ChatProcessor.getEXCLUDE_TOOLS().add(ComfyUIGenerateTool.NAME);
        ChatProcessor.getEXCLUDE_TOOLS().add(ComfyUIResourcesTool.NAME);
        ChatProcessor.getEXCLUDE_TOOLS().add(ComfyUIWorkflowTool.NAME);
    }
}
