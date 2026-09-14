package com.fishsunny.assistant.engine.adapter;

/*
 * @Usage 模型列表条目：id 用于下拉框取值，details 是厂商 /models 响应里该条目除 id 外的原始字段，
 *        不做字段映射，前端直接把 key/value 展示出来，有什么显示什么
 *
 * @Project Assistant
 * @Author FlyingFish-SunnyBear
 * @Date 2026/9/14
 */

import lombok.Data;
import lombok.experimental.Accessors;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Accessors(chain = true)
public class ModelInfo {

    /** 模型 id（下拉框提交的值），已去掉 Gemini 的 models/ 前缀 */
    private String id;

    /** 原始字段（key 原样保留，嵌套对象/数组转成 JSON 字符串）；没有额外字段时为空 Map */
    private Map<String, Object> details = new LinkedHashMap<>();

    public ModelInfo() {
    }
}
