package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage 子 Agent 接口。实现它的组件是专为 agent_tool 服务的普通 Spring 组件，
 *        不再是 ToolHandler：不进任何工具集、不出现在任何工具表里，
 *        主 Agent 只能经 agent_tool(agent=<名称>, target=<任务>) 路由调用其 action()。
 *        自述经 description() 汇入 agent_tool 的子 Agent 大清单，
 *        专属扩展参数经 extensionProperties() 申报，由 AgentTool 统一拼装与透传。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

public interface SubAgentComponent {

    /** 子 Agent 名称：agent_tool 的 agent 参数取值与路由 key */
    String name();

    /** 执行任务。入参是 AgentTool 组装的统一参数（SubAgentArguments），由 agent_tool 路由调用 */
    ToolExecutor.ToolExecuteResponse action(SubAgentComponent.SubAgentArguments arguments, Map<String, Object> context) throws ToolExecutor.ToolExecuteException;

    /** 本子 Agent 的自述文本，拼进 agent_tool 大清单里它自己的介绍条目（见 AgentToolKit.getSubDescription） */
    String description();

    /**
     * 本子 Agent 在 agent_tool 的 extension 参数里要用的扩展字段（type=object 的 properties 节点）。
     * AgentTool 构造时遍历所有子 Agent 调用本方法，把字段平铺聚合成 extension 的 schema——
     * AgentTool 不认识任何具体子 Agent，只负责调用与拼装（好莱坞原则）。
     * <p>
     * 归属不用自己写：参数清单会被拼进子 Agent 大清单里本工具介绍条目的后面，
     * 字段描述保持纯语义即可。默认无扩展字段。
     */
    default List<ToolRegister.Parameters> extensionProperties() {
        return List.of();
    }

    /**
     * 统一入参：AgentTool 路由时组装 target（任务描述）+ extension（主 Agent 按申报字段填写的扩展选项）。
     * 各子 Agent 从 extension 里取自己申报的字段，未申报的键忽略。
     */
    @Data
    @Accessors(chain = true)
    public static class SubAgentArguments {
        private String target;
        private Map<String, Object> extension;
    }
}
