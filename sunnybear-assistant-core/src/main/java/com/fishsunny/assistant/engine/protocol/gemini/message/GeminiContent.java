package com.fishsunny.assistant.engine.protocol.gemini.message;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fishsunny.assistant.engine.protocol.gemini.message.content.GeminiPart;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 一轮对话内容。role 只有 user 和 model 两种取值——没有 system（走顶层 systemInstruction）、
 * 也没有 assistant/tool：模型侧的调用是 model 里的 functionCall part，
 * 工具结果则是 user 里的 functionResponse part。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiContent {

    public static final String ROLE_USER = "user";
    public static final String ROLE_MODEL = "model";

    private String role;

    private List<GeminiPart> parts = new ArrayList<>();

    /** 拷贝一份：相邻同角色 content 合并时会直接往 parts 里追加，不能是 List.of 这类不可变列表 */
    public GeminiContent setParts(List<GeminiPart> parts) {
        this.parts = parts == null ? new ArrayList<>() : new ArrayList<>(parts);
        return this;
    }

    public GeminiContent() {
    }

    public static GeminiContent user(List<GeminiPart> parts) {
        return new GeminiContent().setRole(ROLE_USER).setParts(parts);
    }

    public static GeminiContent model(List<GeminiPart> parts) {
        return new GeminiContent().setRole(ROLE_MODEL).setParts(parts);
    }

    public boolean hasParts() {
        return parts != null && !parts.isEmpty();
    }
}
