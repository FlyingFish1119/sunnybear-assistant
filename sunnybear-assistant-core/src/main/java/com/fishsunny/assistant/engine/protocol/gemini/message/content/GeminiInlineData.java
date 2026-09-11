package com.fishsunny.assistant.engine.protocol.gemini.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Gemini 内联多媒体数据（图片 / 音频 / 视频 / PDF 等），base64 直传。
 * 单请求内联数据总量上限约 20MB，超出需改用 File API（fileData）。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiInlineData {

    /** IANA MIME 类型，如 image/png */
    private String mimeType;

    /** base64 编码的数据本体（不含 data: 前缀） */
    private String data;

    public GeminiInlineData() {
    }

    public GeminiInlineData(String mimeType, String data) {
        this.mimeType = mimeType;
        this.data = data;
    }
}
