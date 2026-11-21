package com.fishsunny.assistant.engine.tool.instance;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.SubAgentComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.agent.AgentTool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "engine.tool.agent.enable", havingValue = "true", matchIfMissing = true)
public class AgentToolKit extends ToolKit {

    public AgentToolKit(List<ToolHandler> tools) {
        // 子 Agent 是独立组件（SubAgentToolHandler），不实现 ToolHandler、不进任何工具表，
        // 这里注册的只有 agent_tool 本体，无需再对子 Agent 做注册后隐藏
        super(tools);
    }

    @Override
    public String displayName() {
        return "子 Agent";
    }

    @Override
    public String description() {
        return "agent_tool 与各类子 Agent 的入口；子 Agent 为独立组件、不作为顶层工具出现，只能经 agent_tool 路由调用";
    }

    /**
     * agent_tool 的 agent 参数描述——按给定路由表列一遍名字。
     * <p>
     * 和 getSubDescription 分开抽，是因为调用方需要把这两处一起换：
     * 只换 function 描述的话，参数说明里那串名字照样把不该出现的子 Agent 广告出去。
     */
    public static String getAgentParamDescription(Map<String, SubAgentComponent> registry) {
        String agentValues = registry.isEmpty() ? "（无）" : String.join(", ", registry.keySet());
        return "子 Agent 类型，可选：" + agentValues;
    }

    public static String getSubDescription(Map<String, SubAgentComponent> registry) {
        StringBuilder description = new StringBuilder("召唤子 Agent 执行任务。子 Agent 拥有各自的领域工具集与执行策略，会自主完成目标并返回结构化结果。");
        if (registry.isEmpty()) {
            description.append("\n\n当前没有可用的子 Agent 类型。");
        } else {
            description.append("\n\n可用子 Agent 类型：\n");
            for (SubAgentComponent subAgent : registry.values()) {
                description.append("- **").append(subAgent.name()).append("**：")
                        .append(subAgent.description()).append("\n");
                // 申报了 extension 字段的子 Agent，把参数清单直接挂在它自己的介绍条目后面——
                // 介绍与参数同处一条，模型读到这条就完整知道怎么调它；
                // 归属由条目结构天然表达，不再需要"适用于 xxx"之类的归属文案
                List<ToolRegister.Parameters> properties = subAgent.extensionProperties();
                if (properties != null && !properties.isEmpty()) {
                    description.append("  专属扩展参数（经本工具的 extension 参数透传）：\n");
                    for (ToolRegister.Parameters property : properties) {
                        description.append("  - ").append(property.getParameterName())
                                .append(" (").append(property.getType()).append(")：")
                                .append(property.getDescription() == null ? "" : property.getDescription())
                                .append("\n");
                    }
                }
            }
        }
        return description.toString();
    }

    /**
     * 按给定 registry 的视角裁剪工具表里 agent_tool 的可召唤说明：
     * 把它的 function 描述与 agent 参数描述换成 getSubDescription / getAgentParamDescription 的 registry 版本。
     * <p>
     * 用途是给子 Agent 执行体裁剪工具表时防自召——AgentTool 的 getRegister 现调现拼、按全部子 Agent
     * 生成，直接给执行体等于告诉它"你可以召唤你自己"。调用方传去掉自己之后的 registry，
     * 子 Agent 互相召唤仍然允许（file_explore、net_explore 都该留着），只有自己不能套自己。
     * <p>
     * 改这份不会污染别人：工具表来自 buildToolRegisterExcluding，每次调用都现造一批新的
     * StandardToolRegister（连同 parameters.properties 里那些对象），不是共享的注册对象。
     * 注意两处都要换：function 描述和 agent 参数的描述各拼了一份名字表，漏一处照样广告出去。
     */
    public static void trimAgentTool(List<StandardToolRegister> toolRegisters, Map<String, SubAgentComponent> registry)
            throws ToolExecutor.ToolExecuteException {
        StandardToolRegister agentToolRegister = toolRegisters.stream()
                .filter(tool -> tool.getFunction().getName().equals(AgentTool.NAME))
                .findFirst()
                .orElseThrow(() -> new ToolExecutor.ToolExecuteException(
                        "工具注册表中不存在 " + AgentTool.NAME + "，无法按 registry 裁剪可召唤说明"));
        agentToolRegister.getFunction().setDescription(getSubDescription(registry));
        agentToolRegister.getFunction().getParameters()
                .getProperties().get(AgentTool.PARAM_AGENT)
                .setDescription(getAgentParamDescription(registry));
    }
}
