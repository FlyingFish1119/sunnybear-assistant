package com.fishsunny.assistant.engine.jev.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Choice 问题：从固定选项集合中选一个。
 * <p>
 * criteria 为「选项名 → 选项描述」的映射；描述可以为 null（选项名本身已足够清晰时）。
 * 单个问题最多 255 个选项，建议附带 other / none of the above 兜底项。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevChoiceQuestion extends JevQuestion {


    private Map<String, String> criteria = new LinkedHashMap<>();

    public JevChoiceQuestion() {
        super.type = "choice";
    }

    public JevChoiceQuestion setCriteria(Map<String, String> criteria) {
        this.criteria = criteria == null ? new LinkedHashMap<>() : criteria;
        return this;
    }
}
