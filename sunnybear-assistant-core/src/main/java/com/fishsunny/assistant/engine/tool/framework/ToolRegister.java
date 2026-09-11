package com.fishsunny.assistant.engine.tool.framework;

/*
 * @Usage
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/6/29 00:40
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.List;

@Data
@Accessors(chain = true)
public class ToolRegister {

    private String name;

    private String description;

    private List<String> required;

    private List<Parameters> parameters;

    /** 工具执行超时时间（毫秒），为 null 表示不限制超时 */
    private Integer timeoutMs;

    public ToolRegister() {
    }

    /**
     * 参数声明，同时兼作 schema 节点：type=object 时用 properties/required 描述字段，
     * type=array 时用 items 描述元素——items 自身又是一个 Parameters，可继续嵌套。
     * <p>Gemini 这类严格实现要求 array 必须带 items，缺了直接 400，所以数组参数务必声明 items。
     */
    @Data
    @Accessors(chain = true)
    public static class Parameters {
        /** 字段名；作为 items 等嵌套节点时无意义，留空即可 */
        private String parameterName;
        private String type;
        private String description;
        /** type=array 时的元素 schema */
        private Parameters items;
        /** type=object 时的字段表 */
        private List<Parameters> properties;
        /** type=object 时的必填字段名 */
        private List<String> required;

        public Parameters() {
        }

        public Parameters(String parameterName, String type, String description) {
            this.parameterName = parameterName;
            this.type = type;
            this.description = description;
        }

        /** 构造数组元素 schema 节点 */
        public static Parameters item(String type, String description) {
            return new Parameters(null, type, description);
        }

        /** 构造对象 schema 节点（数组元素、嵌套字段均可） */
        public static Parameters object(String description, List<Parameters> properties, List<String> required) {
            Parameters schema = new Parameters(null, "object", description);
            schema.setProperties(properties);
            schema.setRequired(required);
            return schema;
        }
    }
}
