package com.fishsunny.assistant.engine.tool.service;

/*
 * @Usage 主对话工具可见性判定 —— 把「哪些 kit / 工具不对主对话开放」收成一处。
 *        两个来源：
 *          1. 代码声明：ToolKit.excludeFromMainAgent()，各 kit 自己声明默认值；
 *          2. 用户配置：ToolKitSettings.visibility（kit 全限定类名 → 是否可见），显式配置覆盖代码声明。
 *        只作用于「全量注入」这条路径（ChatProcessor 与克隆助手）。子 Agent / 角色对话 / 安全审查
 *        走的是 include 语义（buildToolByHandlers / includeKits 的显式允许表），不叠加本判定。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/12
 */

import com.fishsunny.assistant.engine.tool.framework.SubAgentToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.settings.ToolKitSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class ToolVisibilityPolicy {

    /**
     * 两个集合都用 ObjectProvider 而非直接注入，是刻意的 —— 这里有两个必须绕开的构造环：
     * <ol>
     *   <li>工具集：CloneAssistantTool 既是 ToolHandler（在 AgentToolKit 的构造入参里）又要用本策略，
     *       直接注入会形成 policy → List&lt;ToolKit&gt; → AgentToolKit → List&lt;ToolHandler&gt; → CloneAssistantTool → policy；</li>
     *   <li>子 Agent 名单：CloneAssistantTool 本身就在 List&lt;SubAgentToolHandler&gt; 里，构造期取这份名单，
     *       Spring 会把「正在创建中」的自己跳过 → 名单里丢掉 clone_assistant_tool，
     *       主对话就会直接看到它。</li>
     * </ol>
     * 改成每次调用才解析（都是请求期调用，那时单例早已建完），两个环一起避开。
     */
    private final ObjectProvider<List<ToolKit>> toolKits;

    /** 只能经 agent_tool 路由调用的子 Agent 本体 */
    private final ObjectProvider<List<SubAgentToolHandler>> subAgentTools;

    private final ToolKitSettings settings;

    public ToolVisibilityPolicy(ObjectProvider<List<ToolKit>> toolKits,
                                ObjectProvider<List<SubAgentToolHandler>> subAgentTools,
                                ToolKitSettings settings) {
        this.toolKits = toolKits;
        this.subAgentTools = subAgentTools;
        this.settings = settings;
    }

    /**
     * 某个 kit 当前是否对主对话可见。
     * 用户显式配置过就以配置为准（「设置了就开」），否则回落到 kit 自己的代码声明。
     */
    public boolean isVisible(ToolKit kit) {
        Boolean configured = settings.getVisibility().get(kit.getClass().getName());
        return configured != null ? configured : !kit.excludeFromMainAgent();
    }

    /** 主对话需要排除的 kit 类型。每次调用现算，保存设置后立即生效，无需重启 */
    public List<Class<? extends ToolKit>> excludedKits() {
        return toolKits.getObject().stream()
                .filter(kit -> !isVisible(kit))
                .map(ToolVisibilityPolicy::typeOf)
                .toList();
    }

    /** 主对话需要排除的 handler 名（子 Agent 本体，只能经 agent_tool 路由调用） */
    public Set<String> excludedHandlers() {
        return subAgentTools.getObject().stream()
                .map(SubAgentToolHandler::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Class<? extends ToolKit> typeOf(ToolKit kit) {
        return kit.getClass();
    }
}
