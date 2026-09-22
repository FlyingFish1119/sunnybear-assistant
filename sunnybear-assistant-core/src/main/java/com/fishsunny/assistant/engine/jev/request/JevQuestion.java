package com.fishsunny.assistant.engine.jev.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Setter;
import lombok.experimental.Accessors;

/**
 * Jev 问题基类，按 type 字段多态分发。
 * <p>
 * type 取值：{@code choice} / {@code score} / {@code noul}。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonTypeInfo(
        use = JsonTypeInfo.Id.NAME,
        include = JsonTypeInfo.As.EXISTING_PROPERTY,
        property = "type",
        visible = true
)
@JsonSubTypes({
        @JsonSubTypes.Type(value = JevChoiceQuestion.class, name = "choice"),
        @JsonSubTypes.Type(value = JevScoreQuestion.class, name = "score"),
        @JsonSubTypes.Type(value = JevNoulQuestion.class, name = "noul")
})
public abstract class JevQuestion {

    /**
     * 问题类型：choice / score / noul。
     */
    @Setter(AccessLevel.NONE)
    protected String type;

    /**
     * 模型要回答的问题。
     */
    protected String instructions;

    public JevQuestion() {
    }
}
