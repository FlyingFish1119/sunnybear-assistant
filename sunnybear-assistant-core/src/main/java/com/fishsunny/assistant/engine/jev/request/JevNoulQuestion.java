package com.fishsunny.assistant.engine.jev.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

/**
 * Noul 问题：回答一个是 / 否问题，返回「是」的概率。
 * <p>
 * criteria 可选：用 true / false 两个描述界定什么算「是」、什么算「否」。
 * 多数情况下 instructions 已经够用，边界模糊时再加 criteria。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class JevNoulQuestion extends JevQuestion {

    /**
     * 可选的「是 / 否」边界说明。
     */
    private JevNoulCriteria criteria;

    public JevNoulQuestion() {
        super.type = "noul";
    }

    public JevNoulQuestion(String isTrue, String isFalse) {
        super.type = "noul";
        this.criteria = new JevNoulCriteria(isTrue, isFalse);
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class JevNoulCriteria {

        /**
         * 什么算「是」。序列化为 JSON 的 {@code true} 字段。
         */
        @JsonProperty("true")
        private String isTrue;

        /**
         * 什么算「否」。序列化为 JSON 的 {@code false} 字段。
         */
        @JsonProperty("false")
        private String isFalse;

        public JevNoulCriteria() {
        }

        public JevNoulCriteria(String isTrue, String isFalse) {
            this.isTrue = isTrue;
            this.isFalse = isFalse;
        }
    }

}
