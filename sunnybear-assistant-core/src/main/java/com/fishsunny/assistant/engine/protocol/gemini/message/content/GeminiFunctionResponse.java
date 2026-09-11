package com.fishsunny.assistant.engine.protocol.gemini.message.content;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.Map;

/**
 * 工具执行结果回传（请求侧）。
 * response 必须是 JSON 对象（Struct），不能是字符串——字符串结果由适配器包一层 {"result": "..."}。
 * name 必填；id 仅在模型下发过 id 时回传（客户端自造的 id 发回去会导致匹配失败）。
 */
@Data
@Accessors(chain = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class GeminiFunctionResponse {

    private String id;

    private String name;

    /** 函数输出，JSON 对象格式 */
    private Map<String, Object> response;

    public GeminiFunctionResponse() {
    }

    public GeminiFunctionResponse(String id, String name, Map<String, Object> response) {
        this.id = id;
        this.name = name;
        this.response = response;
    }
}
