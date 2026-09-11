package com.fishsunny.assistant.engine.protocol.standard.tools.register;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Map;

/**
 * 工具参数的 schema 节点（JSON Schema 的一个子集片段），随工具声明一起发给模型。
 * 结构可递归：type=object 用 properties/required 描述字段，type=array 用 items 描述元素。
 */
@Data
@Accessors(chain = true)
// 可选字段不序列化：schema 里出现 "items": null 会被严格的实现（如 Gemini）当成非法字段
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StandardToolRegisterProperty {

    private String type;

    private String description;

    /** type=array 时的元素 schema */
    private StandardToolRegisterProperty items;

    /** type=object 时的字段表 */
    private Map<String, StandardToolRegisterProperty> properties;

    /** type=object 时的必填字段名 */
    private List<String> required;

    public StandardToolRegisterProperty() {
    }
}
