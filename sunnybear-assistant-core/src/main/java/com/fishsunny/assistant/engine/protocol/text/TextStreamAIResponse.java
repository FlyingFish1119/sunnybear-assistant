package com.fishsunny.assistant.engine.protocol.text;

import com.fishsunny.assistant.engine.protocol.AIResponse;
import com.fishsunny.assistant.engine.protocol.TokenUsage;
import com.fishsunny.assistant.engine.protocol.UsageSource;
import com.fishsunny.assistant.engine.protocol.standard.response.usage.StandardUsage;
import com.fishsunny.assistant.engine.protocol.text.response.TextStreamChoice;
import lombok.Data;

@Data
public class TextStreamAIResponse implements AIResponse, UsageSource {

    private String id;

    private String object;

    private Long created;

    private String model;

    private TextStreamChoice[] choices = new TextStreamChoice[0];

    public TextStreamAIResponse setChoices(TextStreamChoice[] choices) {
        this.choices = choices == null ? new TextStreamChoice[0] : choices;
        return this;
    }

    /** 顶层 usage：OpenAI 兼容流式 include_usage 时在 finish 帧之后的独立尾帧给出 */
    private StandardUsage usage;

    private String system_fingerprint;

    public TextStreamAIResponse() {
    }

    @Override
    public TokenUsage toTokenUsage() {
        StandardUsage source = usage;
        if (source == null) {
            for (TextStreamChoice choice : choices) {
                if (choice.getUsage() != null) {
                    source = choice.getUsage();
                    break;
                }
            }
        }
        return StandardUsage.toTokenUsage(source);
    }
}
