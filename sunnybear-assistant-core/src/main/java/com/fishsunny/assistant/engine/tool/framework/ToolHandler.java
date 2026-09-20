package com.fishsunny.assistant.engine.tool.framework;

import com.fishsunny.assistant.engine.tool.ToolExecutor;

import java.util.Map;

public interface ToolHandler {

    public ToolExecutor.ToolExecuteResponse action(String arguments, Map<String, Object> context) throws Exception;

    public String name();

    public ToolRegister getRegister();
}
