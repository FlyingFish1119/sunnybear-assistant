package com.fishsunny.assistant.engine.protocol.responses;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * Responses API 的工具声明：<b>扁平结构</b>，字段直接挂在工具对象上。
 *
 * <p>不能复用 {@code StandardToolRegister}——后者是 Chat Completions 形状，把 name / description /
 * parameters 全套在 {@code function} 子对象里，发过去会被拒。
 *
 * @Project sunnybear-assistant-core
 * @Author FlyingFish-SunnyBear
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponsesToolRegister {

    private final String type = "function";

    private String name;

    private String description;

    /** JSON Schema 对象（type / properties / required），不是字符串 */
    private Map<String, Object> parameters;

    public ResponsesToolRegister() {
    }
}
