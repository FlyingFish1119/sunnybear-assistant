package com.fishsunny.assistant.engine.protocol.standard.content.audio;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * OpenAI input_audio 格式:
 * <pre>{@code
 * {
 *   "type": "input_audio",
 *   "input_audio": {
 *     "data": "<base64>",
 *     "format": "wav"
 *   }
 * }
 * }</pre>
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardInputAudio {

    /**
     * 裸 base64 编码的音频数据（不带 data URI 前缀）
     */
    private String data;

    /**
     * 音频格式: "wav" | "mp3"
     */
    private String format;
}
