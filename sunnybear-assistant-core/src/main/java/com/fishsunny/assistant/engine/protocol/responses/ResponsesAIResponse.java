package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * Responses API 非流式响应体。
 *
 * <p>{@code output[]} 的 item 形状与请求 {@code input[]} 是同一套，直接复用 {@link ResponsesItem}。
 * 流式不走这个类，而是每个 SSE 事件一个 {@link ResponsesStreamEvent}。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesAIResponse implements AIResponse {

    private String id;

    private String object;

    /** completed / failed / incomplete / in_progress ... */
    private String status;

    private String model;

    /** 输出 item 列表：message / function_call / reasoning 互为兄弟 */
    private List<ResponsesItem> output;

    private JsonNode usage;

    /** status=incomplete 时给出原因（如 max_output_tokens） */
    private JsonNode incomplete_details;

    /** 失败时的错误对象 */
    private JsonNode error;

    public ResponsesAIResponse() {
    }

    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_INCOMPLETE = "incomplete";
}
