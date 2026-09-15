package com.fishsunny.assistant.engine.protocol.standard;

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import com.fishsunny.assistant.engine.protocol.UsageSource;
import com.fishsunny.assistant.engine.protocol.standard.response.StandardStreamChoice;
import com.fishsunny.assistant.engine.protocol.standard.response.usage.StandardUsage;
import lombok.Data;
import lombok.experimental.Accessors;

@Data
@Accessors(chain = true)
public class StandardStreamAIResponse implements AIResponse, UsageSource {

    private String id;

    private String object;

    private Long created;

    private String model;

    private StandardStreamChoice[] choices = new StandardStreamChoice[0];

    public StandardStreamAIResponse setChoices(StandardStreamChoice[] choices) {
        this.choices = choices == null ? new StandardStreamChoice[0] : choices;
        return this;
    }

    /**
     * 顶层 usage。OpenAI 流式配合 stream_options.include_usage 时，用量在 finish 帧之后的
     * 独立尾帧给出（choices 为空），落在该字段；部分厂商也会直接挂在 choice 上。
     */
    private StandardUsage usage;

    private String system_fingerprint;

    public StandardStreamAIResponse() {
    }

    @Override
    public TokenUsage toTokenUsage() {
        StandardUsage source = usage;
        if (source == null) {
            for (StandardStreamChoice choice : choices) {
                if (choice.getUsage() != null) {
                    source = choice.getUsage();
                    break;
                }
            }
        }
        return StandardUsage.toTokenUsage(source);
    }
}
