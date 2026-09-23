package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage 子 Agent 工具标记接口。实现此接口的工具可被 agent_tool 路由调用：
 *        agent_tool(agent=<工具名>, target=<任务描述>) 会解析出 target 并调用该工具的 action()。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import java.util.List;

public interface SubAgentToolHandler extends ToolHandler {

    /**
     * 本子 Agent 在 agent_tool 的 extension 参数里要用的扩展字段（type=object 的 properties 节点）。
     * AgentTool 构造时遍历所有子 Agent 调用本方法，把字段聚合成 extension 的 schema——
     * AgentTool 不认识任何具体子 Agent，只负责调用与拼装（好莱坞原则）。
     * 默认无扩展字段。
     */
    default List<ToolRegister.Parameters> extensionProperties() {
        return List.of();
    }
}
