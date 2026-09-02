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

    @Data
    @Accessors(chain = true)
    public static class Parameters {
        private String parameterName;
        private String type;
        private String description;
        public Parameters() {
        }
        public Parameters(String parameterName, String type, String description) {
            this.parameterName = parameterName;
            this.type = type;
            this.description = description;
        }
    }
}
