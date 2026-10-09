package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicThinking {

    private String type;

    public AnthropicThinking() {
    }

    public AnthropicThinking(String type) {
        this.type = type;
    }

    /**
     * 自适应思考：想不想思考、思考多深都由模型自己判断，深度改由顶层
     * {@link AnthropicOutputConfig#getEffort()} 控制。
     *
     * <p>新版 Claude 只认这一种取值，旧写法 {@code type=enabled} + {@code budget_tokens}
     * 已被弃用（照旧发会直接 400 invalid_request_error），故不再保留。
     */
    public static AnthropicThinking adaptive() {
        return new AnthropicThinking("adaptive");
    }
}
