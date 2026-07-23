package com.fishsunny.assistant.engine.protocol.standard.chat;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.AIRequest;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.StandardMessage;
import com.fishsunny.assistant.engine.protocol.standard.chat.option.StandardAIThinking;
import com.fishsunny.assistant.engine.protocol.standard.chat.tools.register.StandardToolRegister;
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

    private Boolean stream = false;

    private StandardAIThinking thinking;

    private Double frequency_penalty;

    private Integer max_tokens;

    private Double presence_penalty;

    private Double temperature;

    private Double top_p;

    private String reasoning_effort;

    private List<StandardToolRegister> tools = new ArrayList<>();

    public StandardAIRequest() {
    }
}
