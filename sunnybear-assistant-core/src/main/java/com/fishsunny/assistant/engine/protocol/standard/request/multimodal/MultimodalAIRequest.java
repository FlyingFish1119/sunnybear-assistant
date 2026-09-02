package com.fishsunny.assistant.engine.protocol.standard.request.multimodal;

/*
 * @Usage 多模态 tool 结果协议请求体（OpenAI 兼容格式）。
 *        与 StandardAIRequest 结构一致，但 messages 使用 MultimodalMessage，
 *        tool 消息支持多模态 content 数组。独立协议，不复用 Standard 适配器/消息类。
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 */

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.request.multimodal.message.MultimodalMessage;
import com.fishsunny.assistant.engine.protocol.standard.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.tools.register.StandardToolRegister;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class MultimodalAIRequest implements AIRequest {

    private String model;

    private List<MultimodalMessage> messages = new ArrayList<>();

    public MultimodalAIRequest setMessages(List<MultimodalMessage> messages) {
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

    public MultimodalAIRequest setTools(List<StandardToolRegister> tools) {
        this.tools = tools == null ? new ArrayList<>() : tools;
        return this;
    }

    public MultimodalAIRequest() {
    }

    @Data
    @Accessors(chain = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class ResponseFormat {
        private String type;

        public ResponseFormat() {
        }

        public ResponseFormat(String type) {
            this.type = type;
        }
    }
}
