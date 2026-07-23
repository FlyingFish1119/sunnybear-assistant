package com.fishsunny.assistant.engine.protocol.anthropic.stream;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.JsonNode;
import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.anthropic.AnthropicUsage;
import lombok.Data;

/**
 * Single flat response class for all Anthropic SSE stream events.
 * Like OpenAI's StandardStreamAIResponse — one class, fields populated by Jackson
 * only when present in the JSON payload. Dispatch on {@link #type} in adapter logic.
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnthropicStreamResponse implements AIResponse {

    /** Event type: message_start, content_block_start, content_block_delta, content_block_stop, message_delta, message_stop, ping */
    private String type;

    /** Block index (used by content_block_*) */
    private Integer index;

    /**
     * Varies by event type:
     * <ul>
     *   <li>content_block_delta → parse as {@link AnthropicDelta} (polymorphic on delta.type)</li>
     *   <li>message_delta → manually extract stop_reason / stop_sequence</li>
     * </ul>
     */
    private JsonNode delta;

    /** Present in content_block_start events */
    private AnthropicStreamContentBlock content_block;

    /** Present in message_start events */
    private AnthropicStreamMessage message;

    /** Present in message_delta and message_start events */
    private AnthropicUsage usage;

    /** Present in error events */
    private JsonNode error;

    public AnthropicStreamResponse() {
    }

    // ---- Event type constants ----
    public static final String TYPE_MESSAGE_START = "message_start";
    public static final String TYPE_CONTENT_BLOCK_START = "content_block_start";
    public static final String TYPE_CONTENT_BLOCK_DELTA = "content_block_delta";
    public static final String TYPE_CONTENT_BLOCK_STOP = "content_block_stop";
    public static final String TYPE_MESSAGE_DELTA = "message_delta";
    public static final String TYPE_MESSAGE_STOP = "message_stop";
    public static final String TYPE_PING = "ping";
    public static final String TYPE_ERROR = "error";
}
