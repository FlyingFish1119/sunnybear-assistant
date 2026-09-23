package com.fishsunny.assistant.settings;

/*
 * @Usage 知识库设置 —— 启用开关 + 置信度阈值，与记忆设置的扁平形态一致
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

    /**
     * 知识条目被注入所需的最低置信度：只有「相关」概率高于该值的条目才会被选中。
     * 取值 0-1，默认 0.5；越低召回越多，越高越严格。
     */
    private Double confidenceThreshold;
    public KnowledgeSettings setConfidenceThreshold(Double confidenceThreshold) {
        if (confidenceThreshold == null) {
            this.confidenceThreshold = 0.5;
        } else if (confidenceThreshold < 0) {
            this.confidenceThreshold = 0.0;
        } else if (confidenceThreshold > 1) {
            this.confidenceThreshold = 1.0;
        } else {
            this.confidenceThreshold = confidenceThreshold;
        }
        return this;
    }
    public Double getConfidenceThreshold() {
        return confidenceThreshold == null ? 0.5 : confidenceThreshold;
    }
}
