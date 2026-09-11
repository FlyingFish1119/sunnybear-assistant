package com.fishsunny.assistant.engine.protocol.gemini.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.ArrayList;
import java.util.List;

/**
 * 工具容器：Gemini 把函数声明收在 functionDeclarations 下，而不是像 OpenAI 那样一个工具一个对象。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiTool {

    private List<GeminiFunctionDeclaration> functionDeclarations = new ArrayList<>();

    public GeminiTool setFunctionDeclarations(List<GeminiFunctionDeclaration> functionDeclarations) {
        this.functionDeclarations = functionDeclarations == null ? new ArrayList<>() : functionDeclarations;
        return this;
    }

    public GeminiTool() {
    }

    public static GeminiTool of(List<GeminiFunctionDeclaration> declarations) {
        return new GeminiTool().setFunctionDeclarations(declarations);
    }
}
