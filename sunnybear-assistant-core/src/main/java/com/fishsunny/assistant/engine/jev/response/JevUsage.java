package com.fishsunny.assistant.engine.jev.response;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Jev 用量统计。
 *
 * @author FlyingFish-SunnyBear
 * @since 2026/9/22 10:20
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class JevUsage {

    private Integer input_tokens;

    private Integer output_tokens;

    public JevUsage() {
    }
}
