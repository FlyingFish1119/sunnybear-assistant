package com.fishsunny.assistant.plug.comfyui.tool;

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnProperty(name = "plug.comfyui.tool.enable", havingValue = "true", matchIfMissing = true)
public class ComfyUIToolKit extends ToolKit {

    public ComfyUIToolKit(List<ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "ComfyUI";
    }

    @Override
    public String description() {
        return "文生图工作流的查询、资源浏览与生成";
    }

    /**
     * 本组原子工具由 comfyui_tool 子 Agent 内部使用，普通对话直接调用没有意义；
     * 用户在设置页显式开启后仍可主对话可用。
     */
    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }
}
