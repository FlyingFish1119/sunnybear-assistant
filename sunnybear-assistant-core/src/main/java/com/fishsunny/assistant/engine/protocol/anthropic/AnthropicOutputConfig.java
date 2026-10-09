package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Anthropic 顶层 output_config。
 *
 * <p>当前只用到 {@code effort}：思考深度档位，常见取值 low / medium / high，部分模型还认 max。
 * 它必须挂在请求体顶层，塞进 thinking 对象里属于非法参数。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicOutputConfig {

    private String effort;

    public AnthropicOutputConfig() {
    }

    public AnthropicOutputConfig(String effort) {
        this.effort = effort;
    }
}
