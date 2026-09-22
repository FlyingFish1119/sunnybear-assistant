package com.fishsunny.assistant.engine.jev.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Jev 答案基类，按 type 字段多态分发。
 * <p>
 * type 取值：{@code choice} / {@code score} / {@code noul}。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type", visible = true)
@JsonSubTypes({
        @JsonSubTypes.Type(value = JevChoiceAnswer.class, name = "choice"),
        @JsonSubTypes.Type(value = JevScoreAnswer.class, name = "score"),
        @JsonSubTypes.Type(value = JevNoulAnswer.class, name = "noul")
})
public abstract class JevAnswer {

    /**
     * 答案类型，与请求中问题的 type 对应。
     */
    private String type;

    public JevAnswer() {
    }
}
