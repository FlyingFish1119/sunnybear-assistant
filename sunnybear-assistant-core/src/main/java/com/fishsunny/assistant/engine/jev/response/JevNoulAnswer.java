package com.fishsunny.assistant.engine.jev.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * Noul 答案。
 * <p>
 * noul 是「答案是 / 否」中「是」的概率，0 表示否、1 表示是。
 * 它没有单独的 confidence：只有是 / 否两种结果，单一数值已经完整描述了这个分布。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevNoulAnswer extends JevAnswer {

    /**
     * 答案为「是」的概率，0 ~ 1。
     */
    private Double noul;

    public JevNoulAnswer() {
        setType("noul");
    }
}
