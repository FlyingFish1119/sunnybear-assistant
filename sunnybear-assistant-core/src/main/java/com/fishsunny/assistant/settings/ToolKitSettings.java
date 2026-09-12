package com.fishsunny.assistant.settings;

/*
 * @Usage 工具集可见性设置 —— kit 全限定类名 → 是否对主对话开放。
 *        语义：显式设置过就以用户配置为准；没出现在 map 里的 kit 回落到
 *        ToolKit.excludeFromMainAgent() 的代码声明。因此新装的插件 kit 不会被历史配置误关。
 *        用 map 而不是「排除列表」，是因为排除列表表达不了「把声明排除的 kit 打开」这个反方向。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/12
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ToolKitSettings {

    /** kit 全限定类名 → 是否对主对话可见；缺省项回落到代码声明 */
    private Map<String, Boolean> visibility;

    /** 未配置时返回空 map，调用方一律走 getOrDefault 语义，不必判 null */
    public Map<String, Boolean> getVisibility() {
        return visibility == null ? Map.of() : visibility;
    }

    public ToolKitSettings setVisibility(Map<String, Boolean> visibility) {
        // 丢掉 null 键值：JSON 里的 null 会让「未配置」和「配成 null」两种语义糊在一起
        Map<String, Boolean> cleaned = new LinkedHashMap<>();
        if (visibility != null) {
            visibility.forEach((kitId, visible) -> {
                if (kitId != null && visible != null) {
                    cleaned.put(kitId, visible);
                }
            });
        }
        this.visibility = cleaned;
        return this;
    }
}
