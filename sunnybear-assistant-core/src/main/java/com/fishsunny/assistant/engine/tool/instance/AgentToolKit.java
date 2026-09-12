package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.service.ToolVisibilityPolicy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "engine.tool.agent.enable", havingValue = "true", matchIfMissing = true)
public class AgentToolKit extends ToolKit {

    public AgentToolKit(List<ToolHandler> tools, ToolVisibilityPolicy toolVisibilityPolicy) {
        super(tools);
        tools.stream()
                .filter(tool -> tool instanceof SubAgentToolHandler)
                .forEach(toolVisibilityPolicy::addExcludedHandler);
    }

    @Override
    public String displayName() {
        return "子 Agent";
    }

    @Override
    public String description() {
        return "agent_tool 与各类子 Agent 的入口；子 Agent 本体只能经 agent_tool 路由调用，不会作为顶层工具出现";
    }

    /**
     * agent_tool 的 agent 参数描述——同样按给定路由表列一遍名字。
     * <p>
     * 和 getSubDescription 分开抽，是因为调用方需要把这两处一起换：
     * 只换 function 描述的话，参数说明里那串名字照样把不该出现的子 Agent 广告出去。
     */
    public static String getAgentParamDescription(Map<String, ToolHandler> registry) {
        String agentValues = registry.isEmpty() ? "（无）" : String.join(", ", registry.keySet());
        return "子 Agent 类型，可选：" + agentValues;
    }

    public static String getSubDescription(Map<String, ToolHandler> registry) {
        StringBuilder description = new StringBuilder("召唤子 Agent 执行任务。子 Agent 拥有各自的领域工具集与执行策略，会自主完成目标并返回结构化结果。");
        if (registry.isEmpty()) {
            description.append("\n\n当前没有可用的子 Agent 类型。");
        } else {
            description.append("\n\n可用子 Agent 类型：\n");
            for (ToolHandler subAgent : registry.values()) {
                description.append("- **").append(subAgent.name()).append("**：")
                        .append(subAgent.getRegister().getDescription()).append("\n");
            }
        }
        return description.toString();
    }
}
