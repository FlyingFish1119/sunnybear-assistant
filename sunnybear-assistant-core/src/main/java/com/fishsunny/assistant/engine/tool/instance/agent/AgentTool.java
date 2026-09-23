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
import com.fishsunny.assistant.engine.tool.framework.annotation.ToolKitComponent;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.engine.tool.instance.AgentToolKit;
import lombok.Data;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@ToolKitComponent(AgentToolKit.class)
@ConditionalOnExpression("${engine.tool.agent.enable:true}")
public class AgentTool implements ToolHandler {

    public static final String NAME = "agent_tool";

    /** 子 Agent 类型参数名。克隆体裁剪 agent_tool 描述时也要按这个名字找参数 */
    public static final String PARAM_AGENT = "agent";

    /** 扩展参数名：schema 由各子 Agent 自行申报、此处聚合（好莱坞原则） */
    public static final String PARAM_EXTENSION = "extension";

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

        ToolRegister.Parameters agentParam = new ToolRegister.Parameters()
                .setParameterName(PARAM_AGENT)
                .setType("string")
                .setDescription(AgentToolKit.getAgentParamDescription(registry));

        ToolRegister.Parameters targetParam = new ToolRegister.Parameters()
                .setParameterName("target")
                .setType("string")
                .setDescription("任务描述，描述你希望子 Agent 完成什么。");

        // extension：字段由各子 Agent 自行申报，此处只聚合（好莱坞原则），AgentTool 不认识任何具体字段。
        // 某个子 Agent 不支持的字段透传过去会被它忽略，不影响执行。
        List<ToolRegister.Parameters> extensionProperties = new ArrayList<>();
        for (SubAgentToolHandler subAgent : subAgents) {
            extensionProperties.addAll(subAgent.extensionProperties());
        }

        List<ToolRegister.Parameters> parameters = new ArrayList<>(List.of(agentParam, targetParam));
        if (!extensionProperties.isEmpty()) {
            parameters.add(new ToolRegister.Parameters()
                    .setParameterName(PARAM_EXTENSION)
                    .setType("object")
                    .setDescription("透传给所选子 Agent 的扩展选项（可选）。各字段仅对声明它的子 Agent 生效。")
                    .setProperties(extensionProperties));
        }

        this.register = new ToolRegister()
                .setName(NAME)
                .setDescription(AgentToolKit.getSubDescription(registry))
                .setRequired(List.of(PARAM_AGENT, "target"))
                .setParameters(parameters);
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
            throw new ToolExecutor.ToolExecuteException("未知的子 Agent 类型: " + arguments.getAgent() + "，可选：" + String.join(", ", registry.keySet()));
        }

        // ========== 路由：把 target + extension 转成子 Agent 参数并调用 ==========
        try {
            Map<String, Object> subArguments = new HashMap<>();
            subArguments.put("target", arguments.getTarget());
            if (arguments.getExtension() != null) {
                subArguments.put(PARAM_EXTENSION, arguments.getExtension());
            }
            String subArgumentsJson = objectMapper.writeValueAsString(subArguments);
            return subAgent.action(subArgumentsJson, context);
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
        /** 扩展选项，透传给所选子 Agent；schema 由各子 Agent 自行申报聚合而来 */
        private Map<String, Object> extension;
    }
}
