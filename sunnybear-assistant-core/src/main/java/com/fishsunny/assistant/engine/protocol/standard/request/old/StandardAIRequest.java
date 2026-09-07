package com.fishsunny.assistant.engine.protocol.standard.request.old;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
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
public class StandardAIRequest implements AIRequest {

    private String model;

    private List<StandardMessage> messages = new ArrayList<>();

    public StandardAIRequest setMessages(List<StandardMessage> messages) {
        this.messages = messages == null ? new ArrayList<>() : messages;
        return this;
    }

    private Boolean stream = false;

    private StandardAIThinking thinking;

    private Double frequency_penalty;

    private Integer max_tokens;

    private Double presence_penalty;

    private Double temperature;

    private Double top_p;

    private String reasoning_effort;

    /** 响应格式，null 时不序列化 */
    private ResponseFormat response_format;

    private List<StandardToolRegister> tools = new ArrayList<>();

    public StandardAIRequest setTools(List<StandardToolRegister> tools) {
        this.tools = tools == null ? new ArrayList<>() : tools;
        return this;
    }

    /** 本请求体已序列化的顶层 key，同名自定义字段一律丢弃（内置优先） */
    private static final Set<String> RESERVED_BODY_KEYS = Set.of(
            "model", "messages", "stream", "thinking", "frequency_penalty", "max_tokens",
            "presence_penalty", "temperature", "top_p", "reasoning_effort", "response_format", "tools");

    /** 厂商自定义字段（模型设置里的 customFields），经 setExtraBody 过滤后展开为请求体顶层字段 */
    @JsonIgnore
    private Map<String, Object> extraBody = new LinkedHashMap<>();

    public StandardAIRequest setExtraBody(Map<String, Object> extra) {
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

    public StandardAIRequest() {
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseFormat {
        private String type;

        public ResponseFormat() {}
        public ResponseFormat(String type) { this.type = type; }
    }
}
