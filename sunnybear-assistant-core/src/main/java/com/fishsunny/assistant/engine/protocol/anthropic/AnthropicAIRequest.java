package com.fishsunny.assistant.engine.protocol.anthropic;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.anthropic.message.AnthropicMessage;
import com.fishsunny.assistant.engine.protocol.anthropic.tools.AnthropicToolRegister;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AnthropicAIRequest implements AIRequest {

    private String model;

    private List<AnthropicMessage> messages = new ArrayList<>();

    /**
     * System prompt — Anthropic uses a top-level "system" field instead of
     * a message with role="system". Can be a single string or a list of text blocks.
     */
    private Object system;

    private Boolean stream = false;

    private Integer max_tokens;

    private Double temperature;

    private Double top_p;

    private Integer top_k;

    private List<String> stop_sequences;

    private List<AnthropicToolRegister> tools = new ArrayList<>();

    private AnthropicThinking thinking;

    /** 本请求体已序列化的顶层 key，同名自定义字段一律丢弃（内置优先） */
    private static final Set<String> RESERVED_BODY_KEYS = Set.of(
            "model", "messages", "system", "stream", "max_tokens", "temperature",
            "top_p", "top_k", "stop_sequences", "tools", "thinking");

    /** 厂商自定义字段（模型设置里的 customFields），经 setExtraBody 过滤后展开为请求体顶层字段 */
    @JsonIgnore
    private Map<String, Object> extraBody = new LinkedHashMap<>();

    public AnthropicAIRequest setExtraBody(Map<String, Object> extra) {
        this.extraBody = new LinkedHashMap<>();
        if (extra != null) {
            for (Map.Entry<String, Object> entry : extra.entrySet()) {
                if (!RESERVED_BODY_KEYS.contains(entry.getKey())) {
                    this.extraBody.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return this;
    }

    @JsonAnyGetter
    public Map<String, Object> anyBodyExtra() {
        return extraBody;
    }

    public AnthropicAIRequest() {
    }
}
