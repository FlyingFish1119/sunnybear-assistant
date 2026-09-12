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
import com.fishsunny.assistant.engine.tool.framework.ToolHandler;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.settings.ToolKitSettings;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class ToolVisibilityPolicy {

    private final List<ToolKit> toolKits;

    private final ToolKitSettings settings;

    private final Set<ToolHandler> excludedHandlers = ConcurrentHashMap.newKeySet();
    public void addExcludedHandler(ToolHandler handler) {
        excludedHandlers.add(handler);
    }

    public ToolVisibilityPolicy(@Lazy List<ToolKit> toolKits,
                                ToolKitSettings settings) {
        this.toolKits = toolKits;
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
        return toolKits.stream()
                .filter(kit -> !isVisible(kit))
                .map(ToolVisibilityPolicy::typeOf)
                .collect(Collectors.toUnmodifiableList());
    }

    /** 主对话需要排除的 handler 名（子 Agent 本体，只能经 agent_tool 路由调用） */
    public Set<String> excludedHandlers() {
        return excludedHandlers.stream()
                .map(ToolHandler::name)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static Class<? extends ToolKit> typeOf(ToolKit kit) {
        return kit.getClass();
    }
}
