package com.fishsunny.assistant.settings;

/*
 * @Usage 知识库设置 —— 仅含启用开关，与记忆设置的扁平形态一致
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/20
 */

import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class KnowledgeSettings {

    private Boolean enable;
    public KnowledgeSettings setEnable(Boolean enable) {
        this.enable = Boolean.TRUE.equals(enable);
        return this;
    }
    public Boolean getEnable() {
        return Boolean.TRUE.equals(this.enable);
    }
}
