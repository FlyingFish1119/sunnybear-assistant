package com.fishsunny.assistant.engine.jev.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * Score 答案。
 * <p>
 * score 是层级数轴上的位置，可以落在两层之间，等于「各层级编号 × 其概率」之和。
 * probabilities 以层级编号的字符串为 key（"0"、"1"…）；legend 把层级编号映射回该层描述。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevScoreAnswer extends JevAnswer {

    /**
     * 层级数轴上的位置，范围 0 ~ 最高层级编号。
     */
    private Double score;

    /**
     * 置信度，0 ~ 1。
     */
    private Double confidence;

    /**
     * 层级编号 → 层级描述。描述可能是字符串或对象。
     */
    private Map<String, Object> legend;

    /**
     * 各层级的概率，key 为层级编号的字符串形式，求和为 1。
     */
    private Map<String, Double> probabilities;

    public JevScoreAnswer() {
        setType("score");
    }
}
