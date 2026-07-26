package com.fishsunny.comfyui.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/** /object_info 中单个节点的定义（忽略 input_order / output / category 等未用字段） */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class NodeInfo {
    @JsonProperty("display_name")
    private String displayName;
    private NodeInput input;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class NodeInput {
        private Map<String, List<Object>> required;
    }

    /**
     * 从 required 中提取某个字段的选项列表。
     * 字段值结构为 [optionsArray, metadataObject]。
     */
    @SuppressWarnings("unchecked")
    public List<String> getOptions(String fieldName) {
        if (input == null || input.getRequired() == null) return List.of();
        List<Object> fieldVal = input.getRequired().get(fieldName);
        if (fieldVal == null || fieldVal.isEmpty()) return List.of();
        Object first = fieldVal.get(0);
        if (first instanceof List<?> list) {
            // 确保是 String 列表
            if (!list.isEmpty() && list.get(0) instanceof String) {
                return (List<String>) list;
            }
        }
        return List.of();
    }
}
