package com.fishsunny.assistant.engine.tool.framwork;

/*
 * @Usage 子 Agent 工具标记接口。实现此接口的工具可被 agent_tool 路由调用：
 *        agent_tool(agent=<工具名>, target=<任务描述>) 会解析出 target 并调用该工具的 action()。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

public interface SubAgentToolHandler extends ToolHandler {

}
