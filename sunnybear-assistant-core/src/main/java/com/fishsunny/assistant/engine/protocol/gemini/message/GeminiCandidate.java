package com.fishsunny.assistant.engine.protocol.gemini.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 候选回复。finishReason 为空表示模型还没生成完（流式中间帧），
 * 非空即本轮结束——适配器的 finished() 判定依据。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiCandidate {

    public static final String FINISH_STOP = "STOP";

    private GeminiContent content;

    private String finishReason;

    private String finishMessage;

    private Integer index;

    private List<Map<String, Object>> safetyRatings;

    public GeminiCandidate() {
    }

    /** 本轮是否已结束（finishReason 非空） */
    public boolean isFinished() {
        return finishReason != null && !finishReason.isEmpty();
    }
}
