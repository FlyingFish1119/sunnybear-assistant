package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * 单个 Responses SSE 事件。一个扁平类装下全部事件类型，按 {@link #type} 分派——
 * 与 {@code AnthropicStreamResponse} 同一手法。
 *
 * <p>为什么判别器必须在 JSON 里：{@code ChatHttpHandler} 收流时<b>丢弃 {@code event:} 行</b>，
 * 只把 {@code data:} 的 JSON 交给 Jackson，所以事件类型只能从 data 载荷自己的 {@code type} 字段读。
 * Responses API 正好在每个 data 载荷里都带了 {@code type}。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ResponsesStreamEvent implements AIResponse {

    /** 事件类型，见下方 TYPE_* 常量 */
    private String type;

    /** output item 序号，args 增量与 output_item.* 的关联键 */
    private Integer output_index;

    /** output_text.* 用的 content 分片序号 */
    private Integer content_index;

    /** reasoning_summary_* 用的摘要分片序号（与 content_index 不是同一个字段） */
    private Integer summary_index;

    /** 事件所属 item 的标识 */
    private String item_id;

    /** output_item.added / output_item.done 时的完整 item */
    private ResponsesItem item;

    /**
     * 增量文本。用 JsonNode 而非 String 是因为不同事件的 delta 形态不同
     * （多为字符串，但也有对象形状的），字段类型放宽后，无关事件不会因反序列化失败
     * 而整条流崩掉——取用方按需 asText()。
     */
    private JsonNode delta;

    /** function_call_arguments.done 时的完整参数 JSON 串 */
    private String arguments;

    /** reasoning_summary_part.* 时的摘要分片 */
    private ResponsesContentPart part;

    /** completed / failed / incomplete 时内嵌的完整响应对象 */
    private ResponsesAIResponse response;

    /** type=error 时的错误对象 */
    private JsonNode error;

    public ResponsesStreamEvent() {
    }

    public static final String TYPE_CREATED = "response.created";
    public static final String TYPE_IN_PROGRESS = "response.in_progress";
    public static final String TYPE_OUTPUT_ITEM_ADDED = "response.output_item.added";
    public static final String TYPE_OUTPUT_ITEM_DONE = "response.output_item.done";
    public static final String TYPE_CONTENT_PART_ADDED = "response.content_part.added";
    public static final String TYPE_CONTENT_PART_DONE = "response.content_part.done";
    public static final String TYPE_OUTPUT_TEXT_DELTA = "response.output_text.delta";
    public static final String TYPE_OUTPUT_TEXT_DONE = "response.output_text.done";
    public static final String TYPE_REASONING_SUMMARY_PART_ADDED = "response.reasoning_summary_part.added";
    public static final String TYPE_REASONING_SUMMARY_PART_DONE = "response.reasoning_summary_part.done";
    public static final String TYPE_REASONING_SUMMARY_TEXT_DELTA = "response.reasoning_summary_text.delta";
    public static final String TYPE_REASONING_SUMMARY_TEXT_DONE = "response.reasoning_summary_text.done";
    public static final String TYPE_FUNCTION_CALL_ARGUMENTS_DELTA = "response.function_call_arguments.delta";
    public static final String TYPE_FUNCTION_CALL_ARGUMENTS_DONE = "response.function_call_arguments.done";
    public static final String TYPE_COMPLETED = "response.completed";
    public static final String TYPE_FAILED = "response.failed";
    public static final String TYPE_INCOMPLETE = "response.incomplete";
    public static final String TYPE_ERROR = "error";
}
