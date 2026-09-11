package com.fishsunny.assistant.engine.protocol.gemini.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fishsunny.assistant.engine.protocol.gemini.GeminiAIResponse;

/**
 * 流式响应帧（?alt=sse）。
 * <p>与 Anthropic 不同，Gemini 的 SSE 没有事件类型信封——每个 {@code data:} 行都是一个
 * 完整的 GenerateContentResponse，正文按增量给出，finishReason 只在最后一帧出现。
 * 因此这里直接继承 {@link GeminiAIResponse} 复用同一套字段，仅用于在注册表里区分
 * 流式/非流式两种 targetRespCls。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiStreamResponse extends GeminiAIResponse {

    public GeminiStreamResponse() {
    }
}
