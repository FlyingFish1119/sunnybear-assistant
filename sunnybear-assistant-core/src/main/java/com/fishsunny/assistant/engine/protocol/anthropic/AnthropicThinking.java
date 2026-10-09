package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicThinking {

    private String type;

    /**
     * 思考可见性：{@code summarized} 回思考摘要，{@code omitted} 只回空块 + 加密签名。
     * 不显式设置时新模型（Opus 4.7 起）按 omitted 处理，思考内容一个字都拿不到。
     */
    private String display;

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
     *
     * <p>{@code display} 固定 summarized：省略它等于放弃思考文本——思考照跑照计费，
     * 只是回一个空 thinking block，前端那栏会一片空白。
     */
    public static AnthropicThinking adaptive() {
        return new AnthropicThinking("adaptive").setDisplay("summarized");
    }
}
