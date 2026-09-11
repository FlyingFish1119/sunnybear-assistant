package com.fishsunny.assistant.engine.protocol.gemini.message.content;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 模型发起的函数调用（响应侧）。
 * id 由服务端下发，但并非所有模型都会给（2.5 系列常为空），缺失时适配器自行合成，
 * 合成值带 {@code gemini_call_} 前缀以便回传历史时识别并省略。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class GeminiFunctionCall {

    private String id;

    private String name;

    /** 调用参数，JSON 对象 */
    private Map<String, Object> args;

    public GeminiFunctionCall() {
    }
}
