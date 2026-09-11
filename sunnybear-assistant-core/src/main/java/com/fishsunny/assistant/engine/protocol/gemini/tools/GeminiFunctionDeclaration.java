package com.fishsunny.assistant.engine.protocol.gemini.tools;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 单个函数声明。parameters 为 OpenAPI Schema 子集的对象结构（type/properties/required），
 * 由 StandardToolRegisterParameter 转换而来。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiFunctionDeclaration {

    private String name;

    private String description;

    private Map<String, Object> parameters;

    public GeminiFunctionDeclaration() {
    }
}
