package com.fishsunny.assistant.engine.protocol.standard.request.old;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.request.old.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

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
