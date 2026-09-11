package com.fishsunny.assistant.engine.protocol.gemini.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

/**
 * 函数调用模式配置。mode 取值：AUTO（默认，模型自行决定）、ANY（强制调用，可用
 * allowedFunctionNames 限定范围）、NONE（禁止调用）、VALIDATED。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiFunctionCallingConfig {

    public static final String MODE_AUTO = "AUTO";
    public static final String MODE_ANY = "ANY";
    public static final String MODE_NONE = "NONE";
    public static final String MODE_VALIDATED = "VALIDATED";

    private String mode;

    private List<String> allowedFunctionNames;

    public GeminiFunctionCallingConfig() {
    }
}
