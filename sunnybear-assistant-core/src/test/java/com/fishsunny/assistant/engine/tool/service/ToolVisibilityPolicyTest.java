package com.fishsunny.assistant.engine.tool.service;

import com.fishsunny.assistant.engine.tool.ToolExecutor;
import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.framework.ToolRegister;
import com.fishsunny.assistant.settings.ToolKitSettings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 主对话可见性判定的三条规则：代码声明是默认值、用户配置覆盖声明、未配置回落声明。
 */
class ToolVisibilityPolicyTest {

    /** 普通工具集：代码声明对主对话开放 */
    static class PlainToolKit extends ToolKit {
        PlainToolKit() {
            super(List.of(), true);
        }
    }

    /** 角色 / 子 Agent 专用工具集：代码声明不对主对话开放 */
    static class HiddenToolKit extends ToolKit {
        HiddenToolKit() {
            super(List.of(), true);
        }

        @Override
        public boolean excludeFromMainAgent() {
            return true;
        }
    }

    static class StubSubAgent implements SubAgentToolHandler {
        private final String name;

        StubSubAgent(String name) {
            this.name = name;
        }

        @Override
        public ToolExecutor.ToolExecuteResponse action(String arguments, Map<String, Object> context) {
            return null;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public ToolRegister getRegister() {
            return new ToolRegister().setName(name);
        }
    }

    /** ObjectProvider 只用得到 getObject()，手写一个最小的即可，不必引 Mockito */
    private static <T> ObjectProvider<T> providerOf(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }
        };
    }

    private static ToolVisibilityPolicy policy(ToolKitSettings settings, ToolKit... kits) {
        return new ToolVisibilityPolicy(
                providerOf(List.of(kits)),
                providerOf(List.<SubAgentToolHandler>of(new StubSubAgent("agent_tool"))),
                settings);
    }

    @Test
    void undeclaredKitFollowsCodeDeclaration() {
        PlainToolKit plain = new PlainToolKit();
        HiddenToolKit hidden = new HiddenToolKit();

        ToolVisibilityPolicy policy = policy(new ToolKitSettings(), plain, hidden);

        assertTrue(policy.isVisible(plain));
        assertFalse(policy.isVisible(hidden));
        assertEquals(List.of(HiddenToolKit.class), policy.excludedKits());
    }

    @Test
    void userDisabledKitIsExcluded() {
        PlainToolKit plain = new PlainToolKit();
        ToolKitSettings settings = new ToolKitSettings()
                .setVisibility(Map.of(PlainToolKit.class.getName(), false));

        ToolVisibilityPolicy policy = policy(settings, plain);

        assertFalse(policy.isVisible(plain));
        assertEquals(List.of(PlainToolKit.class), policy.excludedKits());
    }

    /** 「设置了就开」：声明排除的工具集被用户显式打开后应当可见 */
    @Test
    void userConfigOverridesCodeDeclaration() {
        HiddenToolKit hidden = new HiddenToolKit();
        ToolKitSettings settings = new ToolKitSettings()
                .setVisibility(Map.of(HiddenToolKit.class.getName(), true));

        ToolVisibilityPolicy policy = policy(settings, hidden);

        assertTrue(policy.isVisible(hidden));
        assertTrue(policy.excludedKits().isEmpty());
    }

    /** 子 Agent 本体不是 kit 维度的东西，恒在排除名单里，不受可见性配置影响 */
    @Test
    void subAgentHandlersAlwaysExcluded() {
        ToolKitSettings settings = new ToolKitSettings()
                .setVisibility(Map.of("com.fishsunny.assistant.engine.tool.instance.AgentToolKit", true));

        ToolVisibilityPolicy policy = policy(settings, new PlainToolKit());

        assertEquals(List.of("agent_tool"), List.copyOf(policy.excludedHandlers()));
    }
}
