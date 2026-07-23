package com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.audio;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.standard.chat.message.role.content.StandardContent;
import lombok.Data;

/**
 * OpenAI input_audio 内容块
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardAudioContent implements StandardContent {

    private final String type = "input_audio";

    private StandardInputAudio input_audio;

    /**
     * @param base64Data 裸 base64 音频数据
     * @param format     音频格式（如 "wav"、"mp3"）
     */
    public StandardAudioContent(String base64Data, String format) {
        StandardInputAudio inputAudio = new StandardInputAudio();
        inputAudio.setData(base64Data);
        inputAudio.setFormat(format);
        this.input_audio = inputAudio;
    }

    public StandardAudioContent() {
    }
}
