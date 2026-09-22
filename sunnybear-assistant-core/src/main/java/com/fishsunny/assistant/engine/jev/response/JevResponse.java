package com.fishsunny.assistant.engine.jev.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * TypeSafe / Jev System One 响应体。
 * <p>
 * answers 以请求中问题的 id 为 key，一条问题一个答案；usage 为本次调用的 token 统计。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JevResponse {

    /**
     * 实际处理本次请求的模型版本，例如 jev-1.13.0。
     */
    private String model;

    /**
     * 答案集合：key 为请求中自定义的问题 id。
     */
    private Map<String, JevAnswer> answers = new LinkedHashMap<>();

    private JevUsage usage;

    public JevResponse setAnswers(Map<String, JevAnswer> answers) {
        this.answers = answers == null ? new LinkedHashMap<>() : answers;
        return this;
    }

    public JevResponse() {
    }
}
