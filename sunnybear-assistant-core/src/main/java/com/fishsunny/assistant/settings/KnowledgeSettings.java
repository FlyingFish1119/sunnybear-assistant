package com.fishsunny.assistant.settings;

/*
 * @Usage 知识库设置
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

    public static final String API = "api";
    public static final String SETTINGS = "settings";

    private Boolean enable;
    public KnowledgeSettings setEnable(Boolean enable) {
        this.enable = Boolean.TRUE.equals(enable);
        return this;
    }
    public Boolean getEnable() {
        return Boolean.TRUE.equals(this.enable);
    }

    /** 余弦相似度阈值，只有大于等于此值的知识条目才会被匹配 */
    private Float similarityThreshold;
    public KnowledgeSettings setSimilarityThreshold(Float similarityThreshold) {
        this.similarityThreshold = similarityThreshold == null ? 0.7f : similarityThreshold;
        return this;
    }
    public Float getSimilarityThreshold() {
        return similarityThreshold == null ? 0.7f : similarityThreshold;
    }
}
