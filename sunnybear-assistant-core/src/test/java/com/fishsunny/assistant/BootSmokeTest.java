package com.fishsunny.assistant;

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.instance.agent.AgentTool;
import com.fishsunny.assistant.engine.tool.instance.agent.CloneAssistantTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器冒烟测试：验证容器能起来，且克隆助手已正确挂进 agent_tool 的路由表。
 * <p>
 * - RANDOM_PORT：WebSocketConfig 要真实的 ServerContainer，MOCK 环境起不来。
 * - 关掉 bot 工具：MouseMoveTool 等构造时会创建 AWT 对象，无显示环境下抛 HeadlessException。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "engine.tool.bot.enable=false")
class BootSmokeTest {

    @Autowired
    private ToolExecutor toolExecutor;

    @Autowired
    private CloneAssistantTool cloneAssistantTool;

    @Test
    void contextLoads() {
        assertNotNull(toolExecutor);
    }

    @Test
    void cloneAssistantIsRoutable() {
        // getRegister() 返回 null 会让 AgentTool 构造时 NPE，容器根本起不来；
        // 这里能拿到 bean 说明装配通过
        assertNotNull(cloneAssistantTool.getRegister());
        assertEquals(CloneAssistantTool.NAME, cloneAssistantTool.name());

        // 克隆体必须能从路由表里取到
        assertSame(cloneAssistantTool, toolExecutor.getTool(CloneAssistantTool.NAME));

        // agent_tool 的描述里应该已经带上了克隆体这一项（AgentTool 构造时遍历路由表拼出来的）
        AgentTool agentTool = (AgentTool) toolExecutor.getTool(AgentTool.NAME);
        assertNotNull(agentTool);
        assertTrue(agentTool.getRegister().getDescription().contains(CloneAssistantTool.NAME),
                "agent_tool 描述里缺少克隆体：\n" + agentTool.getRegister().getDescription());
    }

}
