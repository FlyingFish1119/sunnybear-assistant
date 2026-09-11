package com.fishsunny.assistant.engine.protocol.gemini.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Gemini 文件引用（File API 上传后拿到的 URI，形如 https://generativelanguage.googleapis.com/v1beta/files/xxx）。
 * 注意：fileUri 只接受 File API URI 或 gs:// 存储桶地址，任意公网 http URL 不受支持。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiFileData {

    /** IANA MIME 类型，如 video/mp4 */
    private String mimeType;

    private String fileUri;

    public GeminiFileData() {
    }

    public GeminiFileData(String mimeType, String fileUri) {
        this.mimeType = mimeType;
        this.fileUri = fileUri;
    }
}
