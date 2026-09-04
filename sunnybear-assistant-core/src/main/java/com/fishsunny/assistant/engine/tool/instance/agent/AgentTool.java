package com.fishsunny.assistant.engine.tool.instance.agent;

/*
 * @Usage 聚合 agent tool —— 路由召唤子 Agent。主 Agent 通过 agent_tool(agent=<类型>, target=<任务>)
 *        调起注册的子 Agent 工具（如 net_explore_tool），由它自主执行并返回结果。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/8/26
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true}")
public class AgentTool implements ToolHandler {

    public static final String NAME = "agent_tool";

    /** 子 Agent 路由表：key = 子 Agent 工具名（name()），value = 子 Agent 工具 */
    private final Map<String, ToolHandler> registry;
    private final ToolRegister register;
    private final ObjectMapper objectMapper;

    public AgentTool(ObjectMapper objectMapper, List<SubAgentToolHandler> subAgents) {
        this.objectMapper = objectMapper;

        this.registry = new HashMap<>();
        for (SubAgentToolHandler subAgent : subAgents) {
            registry.put(subAgent.name(), subAgent);
        }

        // 动态生成描述：枚举可用子 Agent 类型及其能力
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

        String agentValues = registry.isEmpty() ? "（无）" : String.join(", ", registry.keySet());

        ToolRegister.Parameters agentParam = new ToolRegister.Parameters()
                .setParameterName("agent")
                .setType("string")
                .setDescription("子 Agent 类型，可选：" + agentValues);

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("任务描述，描述你希望子 Agent 完成什么。");

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription(description.toString())
                .setRequired(List.of("agent", "target"))
                .setParameters(List.of(agentParam, targetParam));
    }

    @Override
    public ToolExecutor.ToolExecuteResponse action(String argumentsJson, Map<String, Object> context)
            throws ToolExecutor.ToolExecuteException {
        Arguments arguments;
        try {
            arguments = objectMapper.readValue(argumentsJson, Arguments.class);
            if (arguments == null) {
                throw new ToolExecutor.ToolExecuteException("参数为空");
            }
            if (!StringUtils.hasText(arguments.getAgent())) {
                throw new ToolExecutor.ToolExecuteException("参数 agent 不能为空，可选：" + String.join(", ", registry.keySet()));
            }
            if (!StringUtils.hasText(arguments.getTarget())) {
                throw new ToolExecutor.ToolExecuteException("参数 target 不能为空");
            }
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            throw new ToolExecutor.ToolExecuteException("参数解析错误: " + e.getMessage());
        }

        ToolHandler subAgent = registry.get(arguments.getAgent());
        if (subAgent == null) {
            throw new ToolExecutor.ToolExecuteException(
                    "未知的子 Agent 类型: " + arguments.getAgent() + "，可选：" + String.join(", ", registry.keySet()));
        }

        // ========== 路由：把 target 转成子 Agent 参数并调用 ==========
        try {
            String subArguments = objectMapper.writeValueAsString(Map.of("target", arguments.getTarget()));
            return subAgent.action(subArguments, context);
        } catch (ToolExecutor.ToolExecuteException e) {
            throw e;
        } catch (Exception e) {
            log.error("AgentTool 路由子 Agent[{}] 执行异常: {}", subAgent.name(), e.getMessage(), e);
            throw new ToolExecutor.ToolExecuteException("子 Agent[" + subAgent.name() + "] 执行失败: " + e.getMessage());
        }
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolRegister getRegister() {
        return register;
    }

    @Data
    @Accessors(chain = true)
    private static class Arguments {
        private String agent;
        private String target;
    }
}
