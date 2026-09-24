package com.fishsunny.assistant.settings.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fishsunny.assistant.dto.RestResponse;
import com.fishsunny.assistant.engine.tool.framework.ToolKit;
import com.fishsunny.assistant.engine.tool.service.ToolVisibilityPolicy;
import com.fishsunny.assistant.settings.ToolKitSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 工具集（Kit）可见性设置控制器
 * <p>
 * 提供工具集及其工具列表查询，以及 kit 粒度的可见性保存接口。
 *
 * @author FlyingFish-SunnyBear
 * @date 2026/7/4
 */
@RestController
@RequestMapping("/settings")
public class ToolKitSettingsController {

    private static final Logger log = LoggerFactory.getLogger(ToolKitSettingsController.class);

    private final ObjectMapper objectMapper;
    private final String toolKitSettingsPath;
    private final ToolKitSettings toolKitSettings;
    private final List<ToolKit> toolKits;
    private final ToolVisibilityPolicy toolVisibilityPolicy;

    public ToolKitSettingsController(
            ObjectMapper objectMapper,
            @Value("${toolkit-settings.path:settings/toolkit_settings.json}") String toolKitSettingsPath,
            ToolKitSettings toolKitSettings,
            List<ToolKit> toolKits,
            ToolVisibilityPolicy toolVisibilityPolicy) {
        this.objectMapper = objectMapper;
        this.toolKitSettingsPath = toolKitSettingsPath;
        this.toolKitSettings = toolKitSettings;
        this.toolKits = toolKits;
        this.toolVisibilityPolicy = toolVisibilityPolicy;
    }

    // ==================== 工具集（Kit）可见性 ====================

    /**
     * 列出所有已注册的工具集及其工具，供设置页做 kit 粒度的可见性配置。
     * <p>
     * 只反映「主对话全量注入」这条路径的可见性；子 Agent 与角色对话走 include 语义，不受影响。
     */
    @RequestMapping("/tools/kits")
    public RestResponse getToolKits() {
        List<ToolKitView> views = toolKits.stream()
                .map(kit -> new ToolKitView(
                        kit.getClass().getName(),
                        kit.displayName(),
                        kit.description(),
                        !kit.excludeFromMainAgent(),
                        toolVisibilityPolicy.isVisible(kit),
                        kit.getTools().stream()
                                .map(tool -> new ToolView(tool.name(), tool.getRegister().getDescription()))
                                .sorted(Comparator.comparing(ToolView::name))
                                .toList()))
                // 常规工具集在前，默认不对主对话开放的排在后面
                .sorted(Comparator.comparing(ToolKitView::defaultVisible).reversed()
                        .thenComparing(ToolKitView::name))
                .toList();
        return new RestResponse().success(views);
    }

    /**
     * 保存工具集可见性设置。
     * <p>
     * 未知的 kit id 直接忽略并记日志：插件被移除后，不该因为一条历史配置就让整页存不上。
     */
    @PostMapping("/toolkit/save")
    public RestResponse saveToolKitSettings(@RequestBody(required = false) ToolKitSettings settings) {
        if (settings == null) {
            return new RestResponse().error("Invalid settings");
        }
        Set<String> knownIds = toolKits.stream()
                .map(kit -> kit.getClass().getName())
                .collect(Collectors.toSet());
        Map<String, Boolean> sanitized = new LinkedHashMap<>();
        List<String> unknown = new ArrayList<>();
        settings.getVisibility().forEach((kitId, visible) -> {
            if (knownIds.contains(kitId)) {
                sanitized.put(kitId, visible);
            } else {
                unknown.add(kitId);
            }
        });
        if (!unknown.isEmpty()) {
            log.warn("工具集可见性设置里出现未知 kit，已忽略: {}", unknown);
        }
        toolKitSettings.setVisibility(sanitized);
        File settingsFile = new File(toolKitSettingsPath);
        // 首次保存时 settings/ 目录可能还不存在（全新安装），writeValue 不会自己建
        File parent = settingsFile.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            log.error("创建工具集可见性设置目录失败: {}", parent.getAbsolutePath());
            return new RestResponse().error("保存失败");
        }
        try {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(settingsFile, toolKitSettings);
        } catch (Exception e) {
            log.error("保存工具集可见性设置失败: {}", e.getMessage());
            return new RestResponse().error("保存失败");
        }
        return new RestResponse().success("保存成功");
    }

    /** 设置页用的工具集视图：id 是 kit 全限定类名，也是保存时的键 */
    public record ToolKitView(String id,
                              String name,
                              String description,
                              boolean defaultVisible,
                              boolean visible,
                              List<ToolView> tools) {
    }

    public record ToolView(String name, String description) {
    }
}
