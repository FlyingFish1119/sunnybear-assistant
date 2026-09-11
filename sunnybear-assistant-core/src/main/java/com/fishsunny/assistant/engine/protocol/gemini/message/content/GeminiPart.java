package com.fishsunny.assistant.engine.protocol.gemini.message.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

/**
 * Gemini 内容片段：一个 Part 只承载一种载荷，未设置的字段不序列化（NON_NULL）。
 * 采用扁平结构而非多态子类，和 AnthropicStreamContentBlock 的处理方式一致——
 * 请求侧按需 set，响应侧 Jackson 只填 JSON 里出现的字段。
 *
 * <p>思考相关：thought=true 的 part 是思考摘要（不是正文）；thoughtSignature 是加密的推理状态，
 * Gemini 3 在回传多轮历史时必须原样带回，否则可能返回 MISSING_THOUGHT_SIGNATURE。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiPart {

    private String text;

    /** 是否为思考摘要 part（仅响应侧出现；回传历史时保持原样） */
    private Boolean thought;

    /** 思考签名，回传历史时必需（Gemini 3） */
    private String thoughtSignature;

    private GeminiInlineData inlineData;

    private GeminiFileData fileData;

    private GeminiFunctionCall functionCall;

    private GeminiFunctionResponse functionResponse;

    public GeminiPart() {
    }

    public static GeminiPart text(String text) {
        return new GeminiPart().setText(text);
    }

    /**
     * 思考摘要 part。签名非空时一并带上——Gemini 3 要求签名随 part 原样回传。
     */
    public static GeminiPart thought(String text, String signature) {
        return new GeminiPart().setText(text).setThought(true).setThoughtSignature(signature);
    }

    public static GeminiPart inlineData(GeminiInlineData inlineData) {
        return new GeminiPart().setInlineData(inlineData);
    }

    public static GeminiPart fileData(GeminiFileData fileData) {
        return new GeminiPart().setFileData(fileData);
    }

    public static GeminiPart functionCall(GeminiFunctionCall functionCall) {
        return new GeminiPart().setFunctionCall(functionCall);
    }

    public static GeminiPart functionResponse(GeminiFunctionResponse functionResponse) {
        return new GeminiPart().setFunctionResponse(functionResponse);
    }
}
