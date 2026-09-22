package com.fishsunny.assistant.engine.jev.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * Choice 答案。
 * <p>
 * choice 是概率最高的选项；probabilities 是全部选项的完整概率分布（求和为 1）；
 * confidence 由概率分布的分散程度推出：集中在一个选项上则高，分散在多个选项上则低。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevChoiceAnswer extends JevAnswer {

    /**
     * 概率最高的选项名。
     */
    private String choice;

    /**
     * 各选项的概率分布，key 为选项名，求和为 1。
     */
    private Map<String, Double> probabilities;

    /**
     * 置信度，0 ~ 1。
     */
    private Double confidence;

    public JevChoiceAnswer() {
        setType("choice");
    }
}
