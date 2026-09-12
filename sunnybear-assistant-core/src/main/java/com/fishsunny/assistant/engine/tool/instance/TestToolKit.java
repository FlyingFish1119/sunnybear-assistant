package com.fishsunny.assistant.engine.tool.instance;

import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;

import java.util.List;

/**
* TestToolKit
*
* @author FlyingFish-SunnyBear
* @since 2026/9/12 14:42
*/
public class TestToolKit extends ToolKit {

    public TestToolKit(List<? extends ToolHandler> tools) {
        super(tools);
    }

    @Override
    public String displayName() {
        return "工具调用测试";
    }

    @Override
    public String description() {
        return "用于测试工具调用是否正常";
    }

    @Override
    public boolean excludeFromMainAgent() {
        return true;
    }
}