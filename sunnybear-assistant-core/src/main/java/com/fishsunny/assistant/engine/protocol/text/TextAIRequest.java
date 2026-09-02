package com.fishsunny.assistant.engine.protocol.text;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/6 10:11
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import com.fishsunny.assistant.engine.protocol.text.messages.TextMessage;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TextAIRequest implements AIRequest {

    private String model;

    private List<TextMessage> messages = new ArrayList<>();

    public TextAIRequest setMessages(List<TextMessage> messages) {
        this.messages = messages == null ? new ArrayList<TextMessage>() : messages;
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

    public void setTools(List<StandardToolRegister> tools) {
        this.tools = tools == null ? new ArrayList<StandardToolRegister>() : tools;
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
