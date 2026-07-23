package com.fishsunny.assistant.engine.tool.extension;

/*
 * @Usage 扩展脚本元数据，使用 Jackson YAML 解析 tool-extension/ 目录下的 .yaml/.yml 脚本文件
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/7/9
 */

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Data
@Accessors(chain = true)
public class ExtensionScriptMeta {

    private String name;
    private String description;
    private String type;

    @JsonProperty("script")
    private String scriptBody;

    private List<Parameter> parameters;

    /** 脚本文件的绝对路径，运行时注入，不从 YAML 解析 */
    private transient String filePath;

    public ExtensionScriptMeta() {
        this.type = "cmd";
        this.parameters = new ArrayList<>();
    }

    @Data
    @Accessors(chain = true)
    public static class Parameter {
        private String name;
        private String type;
        private String description;
        private boolean required;

        public Parameter() {
            this.type = "string";
        }

        public String toDescription() {
            StringBuilder sb = new StringBuilder();
            sb.append(name).append(" (").append(type);
            if (required) {
                sb.append(", 必填");
            }
            sb.append(")");
            if (description != null && !description.isEmpty()) {
                sb.append(" - ").append(description);
            }
            return sb.toString();
        }
    }

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    /**
     * 从 .yaml / .yml 文件解析脚本元数据。
     */
    public static ExtensionScriptMeta fromYaml(File file) throws IOException {
        ExtensionScriptMeta meta = YAML_MAPPER.readValue(file, ExtensionScriptMeta.class);
        meta.setFilePath(file.getAbsolutePath());

        if (meta.getName() == null || meta.getName().isEmpty()) {
            throw new IOException("脚本文件缺少 name 字段：" + file.getPath());
        }
        if (meta.getScriptBody() == null || meta.getScriptBody().isEmpty()) {
            throw new IOException("脚本文件缺少 script 字段：" + file.getPath());
        }

        return meta;
    }
}
