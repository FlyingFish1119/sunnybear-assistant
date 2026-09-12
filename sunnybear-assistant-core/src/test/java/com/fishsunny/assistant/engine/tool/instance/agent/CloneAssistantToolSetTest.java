package com.fishsunny.assistant.engine.tool.instance.agent;

import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.websocket.processor.ChatProcessor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 克隆体的工具表契约。
 * <p>
 * 核心不变量：克隆体拿得到 agent_tool（子 Agent 之间可以互相召唤），
 * 但 agent_tool 的描述里不能出现它自己——AgentTool 的描述是按全部子 Agent 拼的全局那一份，
 * 忘了替换就等于广告克隆体去召唤自己。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.tool.bot.enable=false")
class CloneAssistantToolSetTest {

    @Autowired
    private CloneAssistantTool cloneAssistantTool;

    @Autowired
    private ToolExecutor toolExecutor;

    private static Set<String> namesOf(List<StandardToolRegister> registers) {
        return registers.stream().map(r -> r.getFunction().getName()).collect(Collectors.toSet());
    }

    private static String descriptionOf(List<StandardToolRegister> registers, String name) {
        return registers.stream()
                .filter(r -> r.getFunction().getName().equals(name))
                .map(r -> r.getFunction().getDescription())
                .findFirst()
                .orElse(null);
    }

    /** agent_tool 的 agent 参数描述里那一份名字表 */
    private static String agentParamDescriptionOf(List<StandardToolRegister> registers) {
        return registers.stream()
                .filter(r -> r.getFunction().getName().equals(AgentTool.NAME))
                .map(r -> r.getFunction().getParameters()
                        .getProperties().get(AgentTool.PARAM_AGENT).getDescription())
                .findFirst()
                .orElse(null);
    }

    @Test
    void cloneSeesAgentToolButNotItself() throws Exception {
        List<StandardToolRegister> toolRegisters = cloneAssistantTool.buildToolRegisters();
        Set<String> names = namesOf(toolRegisters);

        // 有 agent_tool：克隆体可以召唤别的子 Agent
        assertTrue(names.contains(AgentTool.NAME), "克隆体工具表里缺少 " + AgentTool.NAME);

        // 没有自己：clone_assistant_tool 在 ChatProcessor.EXCLUDE_TOOLS 里，本来就不该出现
        assertFalse(names.contains(CloneAssistantTool.NAME),
                "克隆体工具表里出现了它自己：" + CloneAssistantTool.NAME);

        // 子 Agent 工具本身是被 EXCLUDE 的（只能经 agent_tool 路由），不该以顶层工具身份出现
        assertFalse(names.contains(FileExploreTool.NAME));
        assertFalse(names.contains(NetExploreTool.NAME));
    }
}
