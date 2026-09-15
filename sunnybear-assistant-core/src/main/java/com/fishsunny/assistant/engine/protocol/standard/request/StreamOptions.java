package com.fishsunny.assistant.engine.protocol.standard.request;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * OpenAI 兼容的 stream_options。开启 include_usage 后，流式响应的 finish 帧之后会追加一个
 * 独立用量尾帧（choices 为空、usage 有值），供 {@link com.fishsunny.assistant.engine.protocol.UsageSource} 解析。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamOptions {

    private Boolean include_usage;

    public StreamOptions() {
    }

    public StreamOptions(Boolean include_usage) {
        this.include_usage = include_usage;
    }
}
